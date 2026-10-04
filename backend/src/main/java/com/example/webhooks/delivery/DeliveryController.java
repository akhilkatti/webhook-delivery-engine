package com.example.webhooks.delivery;

import com.example.webhooks.queue.DeliveryQueue;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/deliveries")
public class DeliveryController {
    private final DeliveryService service;
    private final DeliveryQueue queue;

    public DeliveryController(DeliveryService service, DeliveryQueue queue) {
        this.service = service;
        this.queue = queue;
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<Map<String, Object>> replay(@PathVariable UUID id) {
        Delivery d = service.resetForReplay(id);     // commits here
        queue.enqueue(d.getId(), Instant.now());     // enqueue after commit
        return ResponseEntity.accepted().body(Map.of("id", d.getId(), "status", d.getStatus()));
    }
}