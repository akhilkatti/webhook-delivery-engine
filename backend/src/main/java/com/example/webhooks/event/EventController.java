package com.example.webhooks.event;

import com.example.webhooks.queue.DeliveryQueue;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/events")
public class EventController {
    public record Accepted(UUID eventId, int deliveries, boolean duplicate) {}

    private final EventService service;
    private final DeliveryQueue queue;

    public EventController(EventService service, DeliveryQueue queue) {
        this.service = service;
        this.queue = queue;
    }

    @PostMapping
    public ResponseEntity<Accepted> ingest(
            @RequestParam String type,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody String payload) {

        // Transaction commits when this call returns...
        var result = service.ingest(type, payload, idempotencyKey);
        // ...and only then do we enqueue, so a worker can never see an ID without its row.
        queue.enqueueAll(result.deliveryIds(), Instant.now());

        var body = new Accepted(result.eventId(), result.deliveryIds().size(), result.duplicate());
        return result.duplicate() ? ResponseEntity.ok(body) : ResponseEntity.accepted().body(body);
    }
}