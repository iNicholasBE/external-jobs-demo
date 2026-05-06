package org.jobrunr.demo.gemini;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Standard Webhooks (https://www.standardwebhooks.com) HMAC-SHA256 verifier.
 *
 * Signed content is "{id}.{timestamp}.{body}". The signature header may
 * contain multiple "v1,<sig>" pairs separated by spaces — any match wins.
 */
public class WebhookSignatureVerifier {

    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private static final String WHSEC_PREFIX = "whsec_";

    private final byte[] keyBytes;

    public WebhookSignatureVerifier(String signingSecret) {
        if (signingSecret == null || signingSecret.isBlank()) {
            throw new IllegalArgumentException("signing secret must not be blank");
        }
        String stripped = signingSecret.startsWith(WHSEC_PREFIX)
                ? signingSecret.substring(WHSEC_PREFIX.length())
                : signingSecret;
        this.keyBytes = Base64.getDecoder().decode(stripped);
    }

    public void verify(String webhookId, String timestamp, String signatureHeader, String body) {
        if (webhookId == null || timestamp == null || signatureHeader == null) {
            throw new SecurityException("missing webhook headers");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new SecurityException("invalid webhook timestamp");
        }
        Instant sent = Instant.ofEpochSecond(ts);
        Instant now = Instant.now();
        if (Duration.between(sent, now).abs().compareTo(MAX_AGE) > 0) {
            throw new SecurityException("webhook timestamp outside tolerance");
        }

        String signedContent = webhookId + "." + timestamp + "." + body;
        byte[] expected = hmac(signedContent.getBytes(StandardCharsets.UTF_8));
        String expectedB64 = Base64.getEncoder().encodeToString(expected);

        for (String pair : signatureHeader.split(" ")) {
            int comma = pair.indexOf(',');
            if (comma < 0) continue;
            String version = pair.substring(0, comma);
            String sig = pair.substring(comma + 1);
            if ("v1".equals(version) && constantTimeEquals(sig, expectedB64)) {
                return;
            }
        }
        throw new SecurityException("no matching signature");
    }

    private byte[] hmac(byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new RuntimeException("HMAC failure", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
