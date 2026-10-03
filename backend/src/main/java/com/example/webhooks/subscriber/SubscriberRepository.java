package com.example.webhooks.subscriber;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface SubscriberRepository extends JpaRepository<Subscriber, UUID> {
    List<Subscriber> findByActiveTrue();
}