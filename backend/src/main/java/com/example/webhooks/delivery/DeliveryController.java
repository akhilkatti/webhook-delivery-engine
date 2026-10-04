package com.example.webhooks.delivery;

import com.example.webhooks.queue.DeliveryQueue;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/deliveries")
public class DeliveryController {
    private final DeliveryService service;
    private final DeliveryQueryService queries;
    private final DeliveryQueue queue;

    public DeliveryController(DeliveryService service, DeliveryQueryService queries, DeliveryQueue queue) {
        this.service = service;
        this.queries = queries;
        this.queue = queue;
    }

    @GetMapping
    public DeliveryQueryService.Page list(
            @RequestParam(required = false) List<String> status,       // ?status=DLQ,RETRYING or repeated
            @RequestParam(required = false) UUID subscriberId,
            @RequestParam(required = false) Integer httpStatus,
            @RequestParam(required = false) Instant from,              // ISO-8601, e.g. 2026-10-01T00:00:00Z
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "createdAt") String sort,     // createdAt | attemptCount | latencyMs
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return queries.list(status, subscriberId, httpStatus, from, to, sort, dir, cursor, limit);
    }

    @GetMapping("/{id}")
    public DeliveryQueryService.Detail get(@PathVariable UUID id) { return queries.get(id); }

    @PostMapping("/{id}/replay")
    public ResponseEntity<Map<String, Object>> replay(@PathVariable UUID id) {
        Delivery d = service.resetForReplay(id);     // commits here
        queue.enqueue(d.getId(), Instant.now());     // enqueue after commit
        return ResponseEntity.accepted().body(Map.of("id", d.getId(), "status", d.getStatus()));
    }
}