package com.example.webhooks.stream;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class DeliveryStreamHub {
    private static final Logger log = LoggerFactory.getLogger(DeliveryStreamHub.class);

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    // Single thread: a slow browser can never block a delivery worker.
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sse-dispatcher");
        t.setDaemon(true);
        return t;
    });

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);                // 0 = no timeout; heartbeats keep proxies awake
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(t -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("hello").data("connected"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        log.info("SSE client connected ({} total)", emitters.size());
        return emitter;
    }

    /** After the DB commit (or immediately if there is no surrounding transaction). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChange(DeliveryChanged change) {
        if (emitters.isEmpty()) return;
        dispatcher.execute(() -> broadcast(change));
    }

    private void broadcast(DeliveryChanged change) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("delivery").data(change, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                emitters.remove(emitter);                       // client went away
            }
        }
    }

    /** Comment line every 15 s: keeps idle connections open and detects dead clients. */
    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    @PreDestroy
    void shutdown() { dispatcher.shutdownNow(); }
}