package com.example.webhooks.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {
    private final RetryPolicy policy = new RetryPolicy(8, 2000, 60_000);

    @Test
    void ceilingDoublesThenCaps() {
        assertEquals(2_000, policy.ceilingMs(1));
        assertEquals(4_000, policy.ceilingMs(2));
        assertEquals(8_000, policy.ceilingMs(3));
        assertEquals(60_000, policy.ceilingMs(10));
        assertEquals(60_000, policy.ceilingMs(500));   // no overflow
    }

    @Test
    void jitteredDelayStaysWithinBounds() {
        for (int attempt = 1; attempt <= 12; attempt++) {
            long ceiling = policy.ceilingMs(attempt);
            for (int i = 0; i < 1_000; i++) {
                long ms = policy.nextDelay(attempt).toMillis();
                assertTrue(ms >= 0 && ms <= ceiling, "attempt " + attempt + " gave " + ms);
            }
        }
    }

    @Test
    void classifiesRetryableFailures() {
        assertTrue(policy.isRetryable(null, "timeout"));
        assertTrue(policy.isRetryable(500, null));
        assertTrue(policy.isRetryable(503, null));
        assertTrue(policy.isRetryable(429, null));
        assertTrue(policy.isRetryable(408, null));
        assertFalse(policy.isRetryable(400, null));
        assertFalse(policy.isRetryable(404, null));
        assertFalse(policy.isRetryable(302, null));
    }
}