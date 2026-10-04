package com.example.webhooks.delivery;

import com.example.webhooks.event.Event;
import com.example.webhooks.event.EventRepository;
import com.example.webhooks.queue.DeliveryQueue;
import com.example.webhooks.ratelimit.RateLimiter;
import com.example.webhooks.security.HmacSigner;
import com.example.webhooks.subscriber.Subscriber;
import com.example.webhooks.subscriber.SubscriberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
public class DeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryRepository deliveries;
    private final EventRepository events;
    private final SubscriberRepository subscribers;
    private final DeliveryQueue queue;
    private final RetryPolicy retry;
    private final HmacSigner signer;
    private final RateLimiter limiter;
    private final HttpClient http;
    private final long timeoutMs;

    public DeliveryWorker(DeliveryRepository deliveries, EventRepository events,
                          SubscriberRepository subscribers, DeliveryQueue queue, RetryPolicy retry,
                          HmacSigner signer, RateLimiter limiter,
                          @Value("${app.delivery.request-timeout-ms:5000}") long timeoutMs) {
        this.deliveries = deliveries;
        this.events = events;
        this.subscribers = subscribers;
        this.queue = queue;
        this.retry = retry;
        this.signer = signer;
        this.limiter = limiter;
        this.timeoutMs = timeoutMs;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    public void process(UUID deliveryId) {
        Delivery d = deliveries.findById(deliveryId).orElse(null);
        if (d == null) { log.warn("delivery {} not found, skipping", deliveryId); return; }
        if (d.getStatus() != DeliveryStatus.PENDING && d.getStatus() != DeliveryStatus.RETRYING) {
            log.info("delivery {} is {}, skipping", deliveryId, d.getStatus());
            return;
        }

        Subscriber sub = subscribers.findById(d.getSubscriberId()).orElse(null);
        Event event = events.findById(d.getEventId()).orElse(null);
        if (sub == null || event == null || !sub.isActive()) {
            d.setStatus(DeliveryStatus.DLQ);
            d.setNextAttemptAt(null);
            d.setLastError("subscriber inactive or missing data");
            deliveries.save(d);
            return;
        }

        // Rate limit BEFORE marking IN_FLIGHT: being throttled is not a failed attempt.
        long waitMs = acquireSlot(sub);
        if (waitMs > 0) {
            Instant dueAt = Instant.now().plusMillis(waitMs + ThreadLocalRandom.current().nextLong(500));
            d.setNextAttemptAt(dueAt);                 // status and attempt_count stay untouched
            deliveries.save(d);
            try {
                queue.enqueue(d.getId(), dueAt);
            } catch (Exception e) {
                log.warn("enqueue failed for {}, recovery job will pick it up: {}", d.getId(), e.getMessage());
            }
            log.info("delivery {} rate limited ({}/min), retry in {} ms", d.getId(), sub.getRateLimitPerMin(), waitMs);
            return;
        }

        d.setStatus(DeliveryStatus.IN_FLIGHT);
        d.setAttemptCount(d.getAttemptCount() + 1);
        deliveries.save(d);

        long start = System.nanoTime();
        Integer httpStatus = null;
        String error = null;
        try {
            byte[] body = event.getPayload().getBytes(UTF_8);          // sign and send these exact bytes
            long timestamp = Instant.now().getEpochSecond();           // fresh timestamp on every attempt
            HttpRequest req = HttpRequest.newBuilder(URI.create(sub.getUrl()))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Id", d.getId().toString())
                    .header("X-Webhook-Event", event.getType())
                    .header("X-Webhook-Signature", signer.header(sub.getSecret(), timestamp, body))
                    .POST(BodyPublishers.ofByteArray(body))
                    .build();
            httpStatus = http.send(req, BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        int latency = (int) ((System.nanoTime() - start) / 1_000_000);

        d.setLatencyMs(latency);
        d.setLastHttpStatus(httpStatus);

        if (httpStatus != null && httpStatus >= 200 && httpStatus < 300) {
            d.setStatus(DeliveryStatus.SUCCESS);
            d.setLastError(null);
            d.setNextAttemptAt(null);
            deliveries.save(d);
            log.info("delivery {} SUCCESS ({} ms, attempt {})", d.getId(), latency, d.getAttemptCount());
            return;
        }

        String reason = error != null ? error : "HTTP " + httpStatus;

        if (!retry.isRetryable(httpStatus, error)) {
            d.setStatus(DeliveryStatus.DLQ);
            d.setNextAttemptAt(null);
            d.setLastError("non-retryable: " + reason);
            deliveries.save(d);
            log.warn("delivery {} -> DLQ (non-retryable: {})", d.getId(), reason);
            return;
        }

        if (d.getAttemptCount() >= retry.maxAttempts()) {
            d.setStatus(DeliveryStatus.DLQ);
            d.setNextAttemptAt(null);
            d.setLastError("max attempts reached: " + reason);
            deliveries.save(d);
            log.warn("delivery {} -> DLQ after {} attempts ({})", d.getId(), d.getAttemptCount(), reason);
            return;
        }

        Duration delay = retry.nextDelay(d.getAttemptCount());
        Instant dueAt = Instant.now().plus(delay);
        d.setStatus(DeliveryStatus.RETRYING);
        d.setNextAttemptAt(dueAt);
        d.setLastError(reason);
        deliveries.save(d);
        try {
            queue.enqueue(d.getId(), dueAt);
        } catch (Exception e) {
            log.warn("enqueue failed for {}, recovery job will pick it up: {}", d.getId(), e.getMessage());
        }
        log.info("delivery {} FAILED ({}), attempt {}/{}, retry in {} ms",
                d.getId(), reason, d.getAttemptCount(), retry.maxAttempts(), delay.toMillis());
    }

    /** Fails open: if Redis hiccups, deliver rather than strand the delivery. Logged loudly. */
    private long acquireSlot(Subscriber sub) {
        try {
            return limiter.tryAcquire(sub.getId(), sub.getRateLimitPerMin());
        } catch (Exception e) {
            log.warn("rate limiter unavailable, failing open: {}", e.getMessage());
            return 0;
        }
    }
}