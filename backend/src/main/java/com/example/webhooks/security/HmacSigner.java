package com.example.webhooks.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Signs "timestamp.body" with HMAC-SHA256. Header format: t=<unix-seconds>,v1=<hex>. */
@Component
public class HmacSigner {
    private static final String ALGO = "HmacSHA256";

    /** Raw HMAC-SHA256 as lowercase hex. Public so it can be checked against published test vectors. */
    public String hmacHex(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), ALGO));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    /** Signature over "<timestamp>.<body>". Binding the timestamp is what enables replay protection. */
    public String sign(String secret, long timestamp, byte[] body) {
        byte[] prefix = (timestamp + ".").getBytes(UTF_8);
        byte[] message = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        System.arraycopy(body, 0, message, prefix.length, body.length);
        return hmacHex(secret, message);
    }

    public String header(String secret, long timestamp, byte[] body) {
        return "t=" + timestamp + ",v1=" + sign(secret, timestamp, body);
    }

    /** Receiver-side check: parses the header, enforces the time window, compares in constant time. */
    public boolean verify(String secret, String header, byte[] body, Duration tolerance, Instant now) {
        if (header == null) return false;
        Long ts = null;
        String sig = null;
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (key.equals("t")) {
                try { ts = Long.parseLong(value); } catch (NumberFormatException e) { return false; }
            } else if (key.equals("v1")) {
                sig = value;
            }
        }
        if (ts == null || sig == null) return false;
        if (Math.abs(now.getEpochSecond() - ts) > tolerance.toSeconds()) return false;   // stale or from the future
        byte[] expected = sign(secret, ts, body).getBytes(UTF_8);
        return MessageDigest.isEqual(expected, sig.getBytes(UTF_8));                     // constant-time
    }
}