package com.example.webhooks.delivery;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
public class DeliveryService {
    private final DeliveryRepository deliveries;

    public DeliveryService(DeliveryRepository deliveries) { this.deliveries = deliveries; }

    /** Resets a DLQ delivery to a fresh PENDING state. Caller enqueues after commit. */
    @Transactional
    public Delivery resetForReplay(UUID id) {
        Delivery d = deliveries.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found"));
        if (d.getStatus() != DeliveryStatus.DLQ) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only DLQ deliveries can be replayed (current status: " + d.getStatus() + ")");
        }
        d.setStatus(DeliveryStatus.PENDING);
        d.setAttemptCount(0);
        d.setNextAttemptAt(Instant.now());
        d.setLastError(null);
        d.setLastHttpStatus(null);
        return d;
    }
}