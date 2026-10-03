package com.example.webhooks.event;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
public class Event {
    @Id
    private UUID id = UUID.randomUUID();
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)   // stored as jsonb
    private String payload;

    private String idempotencyKey;
    private Instant createdAt = Instant.now();

    protected Event() {}

    public Event(String type, String payload, String idempotencyKey) {
        this.type = type;
        this.payload = payload;
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getId() { return id; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
}