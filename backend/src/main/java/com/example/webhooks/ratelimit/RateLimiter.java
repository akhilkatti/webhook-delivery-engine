package com.example.webhooks.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Sliding-window limiter: one ZSET per subscriber holding the timestamps of recent requests. */
@Component
public class RateLimiter {
    private static final long WINDOW_MS = 60_000;

    // Runs atomically inside Redis: purge expired entries, then either take a slot or report the wait.
    private static final String LUA = """
            local key    = KEYS[1]
            local now    = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit  = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            if redis.call('ZCARD', key) < limit then
              redis.call('ZADD', key, now, ARGV[4])
              redis.call('PEXPIRE', key, window)
              return 0
            end
            local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
            return math.max(1, tonumber(oldest[2]) + window - now)
            """;

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(LUA, Long.class);

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    /**
     * Tries to take one slot.
     * @return 0 if allowed, otherwise the milliseconds until the oldest request leaves the window.
     */
    public long tryAcquire(UUID subscriberId, int limitPerMin) {
        Long result = redis.execute(SCRIPT,
                List.of("rl:" + subscriberId),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(WINDOW_MS),
                String.valueOf(Math.max(1, limitPerMin)),
                UUID.randomUUID().toString());          // unique member so same-millisecond calls don't collide
        return result == null ? 0 : result;
    }
}