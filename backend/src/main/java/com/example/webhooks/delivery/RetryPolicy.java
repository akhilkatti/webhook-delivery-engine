package com.example.webhooks.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RetryPolicy {
    private final int maxAttempts;
    private final long baseMs;
    private final long capMs;

    public RetryPolicy(@Value("${app.delivery.max-attempts:8}") int maxAttempts,
                       @Value("${app.delivery.base-delay-ms:2000}") long baseMs,
                       @Value("${app.delivery.cap-delay-ms:3600000}") long capMs) {
        this.maxAttempts = maxAttempts;
        this.baseMs = baseMs;
        this.capMs = capMs;
    }

    public int maxAttempts() { return maxAttempts; }

    /** Upper bound for the delay after the given attempt (1-based): min(cap, base * 2^(attempt-1)). */
    public long ceilingMs(int attempt) {
        int shift = Math.min(Math.max(attempt - 1, 0), 30);   // avoid long overflow
        return Math.min(capMs, baseMs << shift);
    }

    /** Full jitter: uniform random in [0, ceiling]. Spreads retries out so failures don't stampede. */
    public Duration nextDelay(int attempt) {
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceilingMs(attempt) + 1));
    }

    /** Network errors/timeouts, 408, 429 and 5xx are worth retrying. Other 4xx/3xx will never succeed. */
    public boolean isRetryable(Integer httpStatus, String error) {
        if (httpStatus == null) return true;
        return httpStatus == 408 || httpStatus == 429 || httpStatus >= 500;
    }
}