package com.example.webhooks.breaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/** One circuit breaker per subscriber, created lazily. State lives in memory. */
@Component
public class SubscriberBreakers {
    private static final Logger log = LoggerFactory.getLogger(SubscriberBreakers.class);

    public record Snapshot(String state, double failureRate) {}

    private final CircuitBreakerRegistry registry;
    private final Duration openDuration;

    public SubscriberBreakers(
            @Value("${app.breaker.window-size:10}") int windowSize,
            @Value("${app.breaker.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${app.breaker.minimum-calls:5}") int minimumCalls,
            @Value("${app.breaker.open-seconds:30}") long openSeconds,
            @Value("${app.breaker.half-open-calls:2}") int halfOpenCalls) {
        this.openDuration = Duration.ofSeconds(openSeconds);
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(openDuration)
                .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                .build();
        this.registry = CircuitBreakerRegistry.of(config);
        this.registry.getEventPublisher().onEntryAdded(added ->
                added.getAddedEntry().getEventPublisher().onStateTransition(e ->
                        log.warn("circuit breaker {} : {}", e.getCircuitBreakerName(), e.getStateTransition())));
    }

    public CircuitBreaker forSubscriber(UUID subscriberId) {
        return registry.circuitBreaker(subscriberId.toString());
    }

    public Duration openDuration() { return openDuration; }

    /** Read-only view for the API. Doesn't create a breaker for subscribers that never delivered. */
    public Snapshot snapshot(UUID subscriberId) {
        return registry.find(subscriberId.toString())
                .map(cb -> new Snapshot(cb.getState().name(), cb.getMetrics().getFailureRate()))
                .orElse(new Snapshot("CLOSED", -1));
    }
}