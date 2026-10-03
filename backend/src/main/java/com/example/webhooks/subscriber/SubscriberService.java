package com.example.webhooks.subscriber;

import com.example.webhooks.subscriber.SubscriberDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class SubscriberService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SubscriberRepository repo;

    public SubscriberService(SubscriberRepository repo) { this.repo = repo; }

    @Transactional
    public CreatedView create(CreateRequest req) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String secret = "whsec_" + HexFormat.of().formatHex(bytes);
        int limit = req.rateLimitPerMin() != null ? req.rateLimitPerMin() : 60;
        return CreatedView.of(repo.save(new Subscriber(req.name(), req.url(), secret, limit)));
    }

    @Transactional(readOnly = true)
    public List<View> list() {
        return repo.findAll().stream().map(View::of).toList();
    }

    @Transactional(readOnly = true)
    public View get(UUID id) { return View.of(find(id)); }

    @Transactional
    public View update(UUID id, UpdateRequest req) {
        Subscriber s = find(id);
        if (req.name() != null) s.setName(req.name());
        if (req.url() != null) s.setUrl(req.url());
        if (req.rateLimitPerMin() != null) s.setRateLimitPerMin(req.rateLimitPerMin());
        if (req.active() != null) s.setActive(req.active());
        return View.of(s);
    }

    /** Soft delete: deliveries keep a valid foreign key. */
    @Transactional
    public void deactivate(UUID id) { find(id).setActive(false); }

    private Subscriber find(UUID id) {
        return repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscriber not found"));
    }
}