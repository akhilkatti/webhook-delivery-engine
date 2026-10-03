package com.example.webhooks.event;

import com.example.webhooks.delivery.Delivery;
import com.example.webhooks.delivery.DeliveryRepository;
import com.example.webhooks.subscriber.SubscriberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class EventService {
    public record IngestResult(UUID eventId, List<UUID> deliveryIds, boolean duplicate) {}

    private final EventRepository events;
    private final DeliveryRepository deliveries;
    private final SubscriberRepository subscribers;

    public EventService(EventRepository events, DeliveryRepository deliveries,
                        SubscriberRepository subscribers) {
        this.events = events;
        this.deliveries = deliveries;
        this.subscribers = subscribers;
    }

    /** One transaction: event row + one PENDING delivery per active subscriber. */
    @Transactional
    public IngestResult ingest(String type, String payload, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = events.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return new IngestResult(existing.get().getId(), List.of(), true);
            }
        }
        Event event = events.save(new Event(type, payload, idempotencyKey));
        List<Delivery> created = subscribers.findByActiveTrue().stream()
                .map(s -> new Delivery(event.getId(), s.getId()))
                .toList();
        deliveries.saveAll(created);
        return new IngestResult(event.getId(),
                created.stream().map(Delivery::getId).toList(), false);
    }
}