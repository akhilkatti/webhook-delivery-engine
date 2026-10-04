package com.example.webhooks.breaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SubscriberBreakersTest {
    // window 10, open at >=50% failures, judge after 5 calls, 30s open, 2 probes
    private final SubscriberBreakers breakers = new SubscriberBreakers(10, 50f, 5, 30, 2);

    private void fail(CircuitBreaker cb) {
        assertTrue(cb.tryAcquirePermission());
        cb.onError(1, TimeUnit.MILLISECONDS, new RuntimeException("boom"));
    }

    private void ok(CircuitBreaker cb) {
        assertTrue(cb.tryAcquirePermission());
        cb.onSuccess(1, TimeUnit.MILLISECONDS);
    }

    @Test
    void opensAfterRepeatedFailuresAndRejectsCalls() {
        CircuitBreaker cb = breakers.forSubscriber(UUID.randomUUID());
        for (int i = 0; i < 5; i++) fail(cb);
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.tryAcquirePermission());
    }

    @Test
    void staysClosedWhenHealthy() {
        CircuitBreaker cb = breakers.forSubscriber(UUID.randomUUID());
        for (int i = 0; i < 10; i++) ok(cb);
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void doesNotJudgeBeforeMinimumCalls() {
        CircuitBreaker cb = breakers.forSubscriber(UUID.randomUUID());
        for (int i = 0; i < 4; i++) fail(cb);                  // 100% failures, but only 4 calls
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void subscribersAreIsolated() {
        CircuitBreaker bad = breakers.forSubscriber(UUID.randomUUID());
        CircuitBreaker good = breakers.forSubscriber(UUID.randomUUID());
        for (int i = 0; i < 5; i++) fail(bad);
        assertEquals(CircuitBreaker.State.OPEN, bad.getState());
        assertEquals(CircuitBreaker.State.CLOSED, good.getState());
    }
}