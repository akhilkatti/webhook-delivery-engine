package com.example.webhooks.delivery;

import com.example.webhooks.event.Event;
import com.example.webhooks.event.EventRepository;
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

@Component
public class DeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryRepository deliveries;
    private final EventRepository events;
    private final SubscriberRepository subscribers;
    private final HttpClient http;
    private final long timeoutMs;

    public DeliveryWorker(DeliveryRepository deliveries, EventRepository events,
                          SubscriberRepository subscribers,
                          @Value("${app.delivery.request-timeout-ms:5000}") long timeoutMs) {
        this.deliveries = deliveries;
        this.events = events;
        this.subscribers = subscribers;
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
            d.setLastError("subscriber inactive or missing data");
            deliveries.save(d);
            return;
        }

        // Mark in-flight BEFORE the network call; no DB transaction is held during the HTTP request.
        d.setStatus(DeliveryStatus.IN_FLIGHT);
        d.setAttemptCount(d.getAttemptCount() + 1);
        deliveries.save(d);

        long start = System.nanoTime();
        Integer httpStatus = null;
        String error = null;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(sub.getUrl()))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Id", d.getId().toString())
                    .header("X-Webhook-Event", event.getType())
                    .POST(BodyPublishers.ofString(event.getPayload()))
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
            log.info("delivery {} SUCCESS ({} ms, attempt {})", d.getId(), latency, d.getAttemptCount());
        } else {
            // Block 4 will replace this with backoff + re-enqueue + DLQ.
            d.setStatus(DeliveryStatus.RETRYING);
            d.setLastError(error != null ? error : "HTTP " + httpStatus);
            d.setNextAttemptAt(Instant.now());
            log.info("delivery {} FAILED ({}), attempt {}", d.getId(), d.getLastError(), d.getAttemptCount());
        }
        deliveries.save(d);
    }
}