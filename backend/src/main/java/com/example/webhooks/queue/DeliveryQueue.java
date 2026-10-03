package com.example.webhooks.queue;

import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Delay queue: ZSET of deliveryId scored by the epoch-millis it becomes due. */
@Component
public class DeliveryQueue {
    public static final String KEY = "delivery:queue";
    private final StringRedisTemplate redis;

    public DeliveryQueue(StringRedisTemplate redis) { this.redis = redis; }

    public void enqueue(UUID deliveryId, Instant dueAt) {
        redis.opsForZSet().add(KEY, deliveryId.toString(), dueAt.toEpochMilli());
    }

    public void enqueueAll(Collection<UUID> ids, Instant dueAt) {
        if (ids.isEmpty()) return;
        Set<TypedTuple<String>> tuples = ids.stream()
                .map(id -> (TypedTuple<String>) new DefaultTypedTuple<>(id.toString(), (double) dueAt.toEpochMilli()))
                .collect(Collectors.toSet());
        redis.opsForZSet().add(KEY, tuples);
    }
}