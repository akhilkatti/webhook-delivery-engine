package com.example.webhooks.delivery;

import com.example.webhooks.breaker.SubscriberBreakers;
import com.example.webhooks.event.Event;
import com.example.webhooks.event.EventRepository;
import com.example.webhooks.queue.DeliveryQueue;
import com.example.webhooks.ratelimit.RateLimiter;
import com.example.webhooks.security.HmacSigner;
import com.example.webhooks.subscriber.Subscriber;
import com.example.webhooks.subscriber.SubscriberRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
public class DeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    /** Cheap exception (no stack trace) just to tell the breaker "this call failed". */
    private static final class DeliveryFailure extends RuntimeException {
        DeliveryFailure(String message) { super(message, null, false, false); }
    }

    private final DeliveryRepository deliveries;
    private final EventRepository events;
    private final SubscriberRepository subscribers;
    private final DeliveryQueue queue;
    private final RetryPolicy retry;
    private final HmacSigner signer;
    private final RateLimiter limiter;
    private final SubscriberBreakers breakers;
    private final HttpClient http;
    private final long timeoutMs;

    public DeliveryWorker(DeliveryRepository deliveries, EventRepository events,
                          SubscriberRepository subscribers, DeliveryQueue queue, RetryPolicy retry,
                          HmacSigner signer, RateLimiter limiter, SubscriberBreakers breakers,
                          @Value("${app.delivery.request-timeout-ms:5000}") long timeoutMs) {
        this.deliveries = deliveries;
        this.events = events;
        this.subscribers = subscribers;
        this.queue = queue;
        this.retry = retry;
        this.signer = signer;
        this.limiter = limiter;
        this.breakers = breakers;
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

        // 1. Circuit breaker first. Open = don't even try, and don't burn an attempt.
        CircuitBreaker cb = breakers.forSubscriber(sub.getId());
        if (!cb.tryAcquirePermission()) {
            deferForBreaker(d, sub, cb);
            return;
        }

        // From here we hold a breaker permission. It MUST end in onSuccess/onError, or be released.
        boolean reported = false;
        try {
            // 2. Rate limit. Throttled is not a failure, so hand the permission back.
            long waitMs = acquireSlot(sub);
            if (waitMs > 0) {
                Instant dueAt = Instant.now().plusMillis(waitMs + ThreadLocalRandom.current().nextLong(500));
                reschedule(d, dueAt);
                log.info("delivery {} rate limited ({}/min), retry in {} ms",
                        d.getId(), sub.getRateLimitPerMin(), waitMs);
                return;                                   // finally{} releases the permission
            }

            d.setStatus(DeliveryStatus.IN_FLIGHT);
            d.setAttemptCount(d.getAttemptCount() + 1);
            deliveries.save(d);

            long start = System.nanoTime();
            Integer httpStatus = null;
            String error = null;
            boolean interrupted = false;
            try {
                byte[] body = event.getPayload().getBytes(UTF_8);
                long timestamp = Instant.now().getEpochSecond();
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
                interrupted = true;
                error = "interrupted";
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            int latency = (int) ((System.nanoTime() - start) / 1_000_000);

            boolean success = httpStatus != null && httpStatus >= 200 && httpStatus < 300;
            String reason = error != null ? error : "HTTP " + httpStatus;

            // 3. Report to the breaker. "Endpoint unhealthy" = same class of errors we'd retry
            //    (timeouts, connection errors, 408/429/5xx). A 404 or 401 means the server is up.
            if (!interrupted) {
                if (!success && retry.isRetryable(httpStatus, error)) {
                    cb.onError(latency, TimeUnit.MILLISECONDS, new DeliveryFailure(reason));
                } else {
                    cb.onSuccess(latency, TimeUnit.MILLISECONDS);
                }
                reported = true;
            }

            d.setLatencyMs(latency);
            d.setLastHttpStatus(httpStatus);

            if (success) {
                d.setStatus(DeliveryStatus.SUCCESS);
                d.setLastError(null);
                d.setNextAttemptAt(null);
                deliveries.save(d);
                log.info("delivery {} SUCCESS ({} ms, attempt {})", d.getId(), latency, d.getAttemptCount());
                return;
            }

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
            d.setStatus(DeliveryStatus.RETRYING);
            d.setLastError(reason);
            reschedule(d, Instant.now().plus(delay));
            log.info("delivery {} FAILED ({}), attempt {}/{}, retry in {} ms",
                    d.getId(), reason, d.getAttemptCount(), retry.maxAttempts(), delay.toMillis());
        } finally {
            if (!reported) cb.releasePermission();        // never leak a half-open probe slot
        }
    }

    /** Breaker refused the call: park the delivery until the breaker may let it through. No attempt burned. */
    private void deferForBreaker(Delivery d, Subscriber sub, CircuitBreaker cb) {
        long baseMs = cb.getState() == CircuitBreaker.State.HALF_OPEN
                ? 3_000                                   // probes are in progress; check back soon
                : breakers.openDuration().toMillis();     // OPEN: wait out the open period
        long delayMs = baseMs + ThreadLocalRandom.current().nextLong(5_000);   // jitter avoids a stampede
        reschedule(d, Instant.now().plusMillis(delayMs));
        log.info("delivery {} deferred: circuit {} for subscriber {} ({}), retry in {} ms",
                d.getId(), cb.getState(), sub.getName(), sub.getId(), delayMs);
    }

    /** Persist the new due time (DB first), then enqueue. Status and attempt_count are set by the caller. */
    private void reschedule(Delivery d, Instant dueAt) {
        d.setNextAttemptAt(dueAt);
        deliveries.save(d);
        try {
            queue.enqueue(d.getId(), dueAt);
        } catch (Exception e) {
            log.warn("enqueue failed for {}, recovery job will pick it up: {}", d.getId(), e.getMessage());
        }
    }

    /** Fails open: if Redis hiccups, deliver rather than strand the delivery. */
    private long acquireSlot(Subscriber sub) {
        try {
            return limiter.tryAcquire(sub.getId(), sub.getRateLimitPerMin());
        } catch (Exception e) {
            log.warn("rate limiter unavailable, failing open: {}", e.getMessage());
            return 0;
        }
    }
}