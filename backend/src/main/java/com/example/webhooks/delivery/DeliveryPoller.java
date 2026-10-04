package com.example.webhooks.delivery;

import com.example.webhooks.queue.DeliveryQueue;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class DeliveryPoller {
    private static final Logger log = LoggerFactory.getLogger(DeliveryPoller.class);

    private final DeliveryQueue queue;
    private final DeliveryWorker worker;
    private final DeliveryRepository deliveries;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final int batchSize;
    private final Duration stuckAfter;

    public DeliveryPoller(DeliveryQueue queue, DeliveryWorker worker, DeliveryRepository deliveries,
                          @Value("${app.queue.batch-size:20}") int batchSize,
                          @Value("${app.queue.stuck-after-seconds:120}") long stuckAfterSeconds) {
        this.queue = queue;
        this.worker = worker;
        this.deliveries = deliveries;
        this.batchSize = batchSize;
        this.stuckAfter = Duration.ofSeconds(stuckAfterSeconds);
    }

    @Scheduled(fixedDelayString = "${app.queue.poll-interval-ms:5000}")
    public void poll() {
        List<UUID> due;
        try {
            due = queue.dequeueDue(batchSize);
        } catch (Exception e) {
            log.warn("queue poll failed: {}", e.getMessage());
            return;
        }
        for (UUID id : due) {
            executor.submit(() -> {
                try {
                    worker.process(id);
                } catch (Exception e) {
                    // Row stays IN_FLIGHT at worst; the reaper will recover it.
                    log.error("worker crashed on delivery {}", id, e);
                }
            });
        }
    }

    /** Crash safety: a worker that died mid-delivery leaves an IN_FLIGHT row; requeue it. */
    @Scheduled(fixedDelayString = "${app.queue.reaper-interval-ms:60000}")
    public void reapStuck() {
        Instant cutoff = Instant.now().minus(stuckAfter);
        var stuck = deliveries.findByStatusAndUpdatedAtBefore(DeliveryStatus.IN_FLIGHT, cutoff);
        for (Delivery d : stuck) {
            d.setStatus(DeliveryStatus.PENDING);
            d.setLastError("recovered by reaper");
            deliveries.save(d);
            queue.enqueue(d.getId(), Instant.now());
            log.warn("reaper requeued stuck delivery {}", d.getId());
        }
    }

    /**
     * Safety net: rows that are due but not in Redis (Redis was down when we enqueued,
     * or the ZSET was lost) get re-enqueued. Postgres is the source of truth.
     */
    @Scheduled(fixedDelayString = "${app.queue.recovery-interval-ms:60000}")
    public void recoverOrphans() {
        Instant cutoff = Instant.now().minusSeconds(60);   // grace period so we don't race live workers
        var orphans = deliveries.findTop100ByStatusInAndNextAttemptAtBefore(
                List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING), cutoff);
        for (Delivery d : orphans) {
            queue.enqueueIfAbsent(d.getId(), Instant.now());
            log.warn("recovery re-enqueued delivery {} ({})", d.getId(), d.getStatus());
        }
    }

    @PreDestroy
    void shutdown() { executor.shutdown(); }
}