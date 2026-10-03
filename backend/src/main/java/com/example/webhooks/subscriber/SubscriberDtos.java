package com.example.webhooks.subscriber;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class SubscriberDtos {
    private SubscriberDtos() {}

    public record CreateRequest(
            @NotBlank String name,
            @NotBlank @Pattern(regexp = "^https?://.+", message = "must be an http(s) URL") String url,
            @Min(1) @Max(100000) Integer rateLimitPerMin) {}

    public record UpdateRequest(
            String name,
            @Pattern(regexp = "^https?://.+", message = "must be an http(s) URL") String url,
            @Min(1) @Max(100000) Integer rateLimitPerMin,
            Boolean active) {}

    /** Normal view: never includes the secret. */
    public record View(UUID id, String name, String url, int rateLimitPerMin,
                       boolean active, Instant createdAt) {
        static View of(Subscriber s) {
            return new View(s.getId(), s.getName(), s.getUrl(), s.getRateLimitPerMin(),
                    s.isActive(), s.getCreatedAt());
        }
    }

    /** Returned once, at creation: the only time the secret is shown. */
    public record CreatedView(UUID id, String name, String url, int rateLimitPerMin,
                              boolean active, Instant createdAt, String secret) {
        static CreatedView of(Subscriber s) {
            return new CreatedView(s.getId(), s.getName(), s.getUrl(), s.getRateLimitPerMin(),
                    s.isActive(), s.getCreatedAt(), s.getSecret());
        }
    }
}