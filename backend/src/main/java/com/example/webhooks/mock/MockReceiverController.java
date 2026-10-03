package com.example.webhooks.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.ThreadLocalRandom;

/** Fake customer endpoint for demos/tests: ~10% hang, ~failRate return 500, rest 200. */
@RestController
@RequestMapping("/mock")
public class MockReceiverController {
    private static final Logger log = LoggerFactory.getLogger(MockReceiverController.class);

    @PostMapping("/{key}")
    public ResponseEntity<String> receive(
            @PathVariable String key,
            @RequestBody String body,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestParam(defaultValue = "0.3") double failRate) throws InterruptedException {

        double r = ThreadLocalRandom.current().nextDouble();
        if (r < 0.10) {
            log.info("mock[{}] hanging (simulated timeout)", key);
            Thread.sleep(8000);                       // longer than the 5s client timeout
            return ResponseEntity.ok("late");
        }
        if (r < 0.10 + failRate) {
            log.info("mock[{}] returning 500", key);
            return ResponseEntity.status(500).body("simulated failure");
        }
        log.info("mock[{}] OK sig={} body={}", key, signature, body);
        return ResponseEntity.ok("received");
    }
}