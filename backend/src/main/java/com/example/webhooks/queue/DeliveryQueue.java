package com.example.webhooks.queue;

import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Delay queue: ZSET of deliveryId scored by the epoch-millis it becomes due. */
@Component
public class DeliveryQueue {
    public static final String KEY = "delivery:queue";

    // Atomic: find due IDs and remove them in one step, so two workers can never grab the same one.
    private static final String DEQUEUE_LUA = """
            local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
            for _, id in ipairs(ids) do redis.call('ZREM', KEYS[1], id) end
            return ids
            """;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final DefaultRedisScript<List> DEQUEUE = new DefaultRedisScript<>(DEQUEUE_LUA, List.class);

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

    /** Pops up to {@code batch} deliveries whose due time has passed. */
    public List<UUID> dequeueDue(int batch) {
        List<?> raw = redis.execute(DEQUEUE, List.of(KEY),
                String.valueOf(System.currentTimeMillis()), String.valueOf(batch));
        if (raw == null) return List.of();
        return raw.stream().map(o -> UUID.fromString(o.toString())).toList();
    }

    /** Adds only if the ID isn't already queued, so it never overwrites an existing due time. */
    public void enqueueIfAbsent(UUID deliveryId, Instant dueAt) {
        redis.opsForZSet().addIfAbsent(KEY, deliveryId.toString(), dueAt.toEpochMilli());
    }
}