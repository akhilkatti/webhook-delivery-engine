package com.example.webhooks.delivery;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import com.example.webhooks.stream.DeliveryChanged;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;
import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "deliveries")
public class Delivery {
    @Id
    private UUID id = UUID.randomUUID();
    private UUID eventId;
    private UUID subscriberId;

    @Enumerated(EnumType.STRING)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    private int attemptCount = 0;
    private Instant nextAttemptAt = Instant.now();
    private Integer lastHttpStatus;
    private String lastError;
    private Integer latencyMs;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    protected Delivery() {}

    public Delivery(UUID eventId, UUID subscriberId) {
        this.eventId = eventId;
        this.subscriberId = subscriberId;
    }

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }
    /** Spring Data calls this on every repository save(). */
    @DomainEvents
    public Collection<Object> domainEvents() {
        return List.of(new DeliveryChanged(id, subscriberId, status.name(), attemptCount,
                lastHttpStatus, lastError, latencyMs, Instant.now()));
    }

    @AfterDomainEventPublication
    public void clearDomainEvents() { /* nothing to clear: events are built on demand */ }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getSubscriberId() { return subscriberId; }
    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus s) { this.status = s; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int n) { this.attemptCount = n; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant t) { this.nextAttemptAt = t; }
    public Integer getLastHttpStatus() { return lastHttpStatus; }
    public void setLastHttpStatus(Integer s) { this.lastHttpStatus = s; }
    public String getLastError() { return lastError; }
    public void setLastError(String e) { this.lastError = e; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer ms) { this.latencyMs = ms; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}