package com.example.webhooks.delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {
    List<Delivery> findByStatusAndUpdatedAtBefore(DeliveryStatus status, Instant cutoff);
}