package com.example.webhooks.subscriber;

import com.example.webhooks.subscriber.SubscriberDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/subscribers")
public class SubscriberController {
    private final SubscriberService service;

    public SubscriberController(SubscriberService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedView create(@Valid @RequestBody CreateRequest req) { return service.create(req); }

    @GetMapping
    public List<View> list() { return service.list(); }

    @GetMapping("/{id}")
    public View get(@PathVariable UUID id) { return service.get(id); }

    @PutMapping("/{id}")
    public View update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID id) { service.deactivate(id); }
}