package com.example.webhooks.delivery;

import com.example.webhooks.breaker.SubscriberBreakers;
import com.example.webhooks.event.Event;
import com.example.webhooks.event.EventRepository;
import com.example.webhooks.queue.DeliveryQueue;
import com.example.webhooks.ratelimit.RateLimiter;
import com.example.webhooks.security.HmacSigner;
import com.example.webhooks.subscriber.Subscriber;
import com.example.webhooks.subscriber.SubscriberRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Real HTTP server + real worker/retry/signer logic; storage and Redis are mocked. */
class DeliveryFailurePathTest {
    private HttpServer server;
    private final AtomicInteger responseStatus = new AtomicInteger(500);
    private final AtomicReference<String> signatureHeader = new AtomicReference<>();
    private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();

    private final DeliveryRepository deliveries = mock(DeliveryRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final SubscriberRepository subscribers = mock(SubscriberRepository.class);
    private final DeliveryQueue queue = mock(DeliveryQueue.class);
    private final RateLimiter limiter = mock(RateLimiter.class);       // returns 0 = never throttled
    private final HmacSigner signer = new HmacSigner();

    private Subscriber sub;
    private Delivery delivery;
    private DeliveryWorker worker;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            receivedBody.set(ex.getRequestBody().readAllBytes());
            signatureHeader.set(ex.getRequestHeaders().getFirst("X-Webhook-Signature"));
            ex.sendResponseHeaders(responseStatus.get(), -1);
            ex.close();
        });
        server.start();

        sub = new Subscriber("test", "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", "whsec_test", 60);
        Event event = new Event("order.created", "{\"orderId\":1}", null);
        delivery = new Delivery(event.getId(), sub.getId());

        when(deliveries.findById(delivery.getId())).thenReturn(Optional.of(delivery));
        when(events.findById(event.getId())).thenReturn(Optional.of(event));
        when(subscribers.findById(sub.getId())).thenReturn(Optional.of(sub));

        worker = new DeliveryWorker(deliveries, events, subscribers, queue,
                new RetryPolicy(3, 1, 5),                       // 3 attempts, tiny delays
                signer, limiter, new SubscriberBreakers(10, 50f, 5, 30, 2), 2000);
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void failingEndpointRetriesThenLandsInDlq() {
        responseStatus.set(500);

        worker.process(delivery.getId());
        assertEquals(DeliveryStatus.RETRYING, delivery.getStatus());
        assertEquals(1, delivery.getAttemptCount());

        worker.process(delivery.getId());
        assertEquals(DeliveryStatus.RETRYING, delivery.getStatus());
        assertEquals(2, delivery.getAttemptCount());

        worker.process(delivery.getId());                       // third failure exhausts max-attempts
        assertEquals(DeliveryStatus.DLQ, delivery.getStatus());
        assertEquals(3, delivery.getAttemptCount());
        assertTrue(delivery.getLastError().startsWith("max attempts reached"));

        verify(queue, times(2)).enqueue(eq(delivery.getId()), any(Instant.class));   // none after DLQ
    }

    @Test
    void permanentClientErrorGoesStraightToDlq() {
        responseStatus.set(404);

        worker.process(delivery.getId());

        assertEquals(DeliveryStatus.DLQ, delivery.getStatus());
        assertEquals(1, delivery.getAttemptCount());
        assertEquals(404, delivery.getLastHttpStatus());
        verify(queue, never()).enqueue(any(), any());
    }

    @Test
    void successfulDeliveryCarriesAVerifiableSignature() {
        responseStatus.set(200);

        worker.process(delivery.getId());

        assertEquals(DeliveryStatus.SUCCESS, delivery.getStatus());
        assertTrue(signer.verify(sub.getSecret(), signatureHeader.get(), receivedBody.get(),
                Duration.ofMinutes(5), Instant.now()));
    }
}