package com.example.webhooks.mock;

import com.example.webhooks.security.HmacSigner;
import com.example.webhooks.subscriber.Subscriber;
import com.example.webhooks.subscriber.SubscriberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Fake customer endpoint. If {key} is a subscriber UUID, the signature is verified against that
 * subscriber's secret (401 on failure). Then: hangRate -> timeout, failRate -> 500, else 200.
 */
@RestController
@RequestMapping("/mock")
public class MockReceiverController {
    private static final Logger log = LoggerFactory.getLogger(MockReceiverController.class);
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final SubscriberRepository subscribers;
    private final HmacSigner signer;

    public MockReceiverController(SubscriberRepository subscribers, HmacSigner signer) {
        this.subscribers = subscribers;
        this.signer = signer;
    }

    @PostMapping("/{key}")
    public ResponseEntity<String> receive(
            @PathVariable String key,
            @RequestBody byte[] body,                       // raw bytes: this is what you must verify
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestParam(defaultValue = "0.3") double failRate,
            @RequestParam(defaultValue = "0.10") double hangRate) throws InterruptedException {

        String verdict = "unchecked";
        UUID subscriberId = parseUuid(key);
        Subscriber sub = subscriberId == null ? null : subscribers.findById(subscriberId).orElse(null);
        if (sub != null) {
            if (!signer.verify(sub.getSecret(), signature, body, TOLERANCE, Instant.now())) {
                log.warn("mock[{}] INVALID signature: {}", key, signature);
                return ResponseEntity.status(401).body("invalid signature");
            }
            verdict = "valid";
        }

        double r = ThreadLocalRandom.current().nextDouble();
        if (r < hangRate) {
            log.info("mock[{}] hanging (simulated timeout)", key);
            Thread.sleep(8000);                             // longer than the 5s client timeout
            return ResponseEntity.ok("late");
        }
        if (r < hangRate + failRate) {
            log.info("mock[{}] returning 500", key);
            return ResponseEntity.status(500).body("simulated failure");
        }
        log.info("mock[{}] OK signature={} body={}", key, verdict, new String(body, UTF_8));
        return ResponseEntity.ok("received");
    }

    private static UUID parseUuid(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}