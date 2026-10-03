package com.example.webhooks.subscriber;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subscribers")
public class Subscriber {
    @Id
    private UUID id = UUID.randomUUID();
    private String name;
    private String url;
    private String secret;
    private int rateLimitPerMin = 60;
    private boolean active = true;
    private Instant createdAt = Instant.now();

    protected Subscriber() {}

    public Subscriber(String name, String url, String secret, int rateLimitPerMin) {
        this.name = name;
        this.url = url;
        this.secret = secret;
        this.rateLimitPerMin = rateLimitPerMin;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getSecret() { return secret; }
    public int getRateLimitPerMin() { return rateLimitPerMin; }
    public void setRateLimitPerMin(int v) { this.rateLimitPerMin = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
}