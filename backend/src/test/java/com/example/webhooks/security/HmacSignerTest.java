package com.example.webhooks.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class HmacSignerTest {
    private final HmacSigner signer = new HmacSigner();
    private final byte[] body = "{\"orderId\":1001}".getBytes(UTF_8);
    private final Duration tolerance = Duration.ofMinutes(5);

    @Test
    void matchesRfc4231TestCase2() {
        // Published test vector: key "Jefe", data "what do ya want for nothing?"
        String mac = signer.hmacHex("Jefe", "what do ya want for nothing?".getBytes(UTF_8));
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac);
    }

    @Test
    void signatureCoversTimestampDotBody() {
        String expected = signer.hmacHex("secret", "1700000000.{\"orderId\":1001}".getBytes(UTF_8));
        assertEquals(expected, signer.sign("secret", 1700000000L, body));
    }

    @Test
    void acceptsValidSignature() {
        long ts = Instant.now().getEpochSecond();
        String header = signer.header("secret", ts, body);
        assertTrue(signer.verify("secret", header, body, tolerance, Instant.now()));
    }

    @Test
    void rejectsTamperedBody() {
        long ts = Instant.now().getEpochSecond();
        String header = signer.header("secret", ts, body);
        byte[] tampered = "{\"orderId\":9999}".getBytes(UTF_8);
        assertFalse(signer.verify("secret", header, tampered, tolerance, Instant.now()));
    }

    @Test
    void rejectsWrongSecret() {
        long ts = Instant.now().getEpochSecond();
        String header = signer.header("secret", ts, body);
        assertFalse(signer.verify("other-secret", header, body, tolerance, Instant.now()));
    }

    @Test
    void rejectsReplayedOldSignature() {
        long old = Instant.now().minusSeconds(600).getEpochSecond();
        String header = signer.header("secret", old, body);
        assertFalse(signer.verify("secret", header, body, tolerance, Instant.now()));
    }

    @Test
    void rejectsMalformedHeaders() {
        Instant now = Instant.now();
        assertFalse(signer.verify("secret", null, body, tolerance, now));
        assertFalse(signer.verify("secret", "garbage", body, tolerance, now));
        assertFalse(signer.verify("secret", "t=abc,v1=ff", body, tolerance, now));
        assertFalse(signer.verify("secret", "t=" + now.getEpochSecond(), body, tolerance, now));
    }
}