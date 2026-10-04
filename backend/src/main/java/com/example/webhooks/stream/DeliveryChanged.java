package com.example.webhooks.stream;

import java.time.Instant;
import java.util.UUID;

/** Small payload pushed to browsers on every delivery state change. */
public record DeliveryChanged(UUID id, UUID subscriberId, String status, int attemptCount,
                              Integer lastHttpStatus, String lastError, Integer latencyMs, Instant updatedAt) {}