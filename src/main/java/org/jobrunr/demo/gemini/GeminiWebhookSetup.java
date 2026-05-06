package org.jobrunr.demo.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * On startup, ensures a static webhook is registered with Gemini for this app's
 * public URL. The signing secret is persisted in a local (gitignored) file so
 * subsequent restarts reuse the same registration.
 *
 * If GEMINI_API_KEY or GEMINI_PUBLIC_URL is missing, this skips silently —
 * the demo page will render a warning instead.
 */
@Component
public class GeminiWebhookSetup {

    // Supported by Gemini today: batch.succeeded, batch.expired, batch.failed,
    // interaction.requires_action, interaction.completed, interaction.failed, video.generated.
    // (batch.cancelled and interaction.cancelled appear in the docs but are rejected by the API.)
    private static final List<String> SUBSCRIBED_EVENTS = List.of(
            "batch.succeeded", "batch.failed", "batch.expired", "video.generated");

    private final GeminiClient client;
    private final GeminiConfig config;
    private final WebhookSecretStore store;

    private volatile WebhookSignatureVerifier verifier;
    private volatile String webhookId;
    private volatile String setupError;

    public GeminiWebhookSetup(GeminiClient client, GeminiConfig config, ObjectMapper mapper) {
        this.client = client;
        this.config = config;
        this.store = new WebhookSecretStore(Paths.get(config.secretFile()), mapper);
    }

    @PostConstruct
    public void register() {
        if (!config.isConfigured()) {
            System.out.println("[gemini] GEMINI_API_KEY and/or GEMINI_PUBLIC_URL not set — skipping webhook registration.");
            return;
        }
        try {
            String webhookUrl = config.webhookUrl();
            Optional<WebhookSecretStore.StoredWebhook> existing = store.load();
            boolean canReuse = existing.isPresent()
                    && existing.get().uri().equals(webhookUrl)
                    && new HashSet<>(existing.get().events()).equals(new HashSet<>(SUBSCRIBED_EVENTS));
            if (canReuse) {
                this.webhookId = existing.get().id();
                this.verifier = new WebhookSignatureVerifier(existing.get().signingSecret());
                System.out.println("[gemini] Reusing webhook " + webhookId + " → " + webhookUrl);
                return;
            }
            if (existing.isPresent()) {
                System.out.println("[gemini] Webhook config changed, deleting " + existing.get().id());
                try { client.deleteWebhook(existing.get().id()); } catch (Exception ignored) {}
                store.clear();
            }
            String name = "external-jobs-demo-" + System.currentTimeMillis();
            GeminiClient.WebhookInfo info = client.createWebhook(name, SUBSCRIBED_EVENTS, webhookUrl);
            if (info.signingSecret() == null || info.signingSecret().isBlank()) {
                throw new IllegalStateException("Gemini did not return new_signing_secret on webhook create");
            }
            store.save(new WebhookSecretStore.StoredWebhook(info.id(), webhookUrl, info.signingSecret(), SUBSCRIBED_EVENTS));
            this.webhookId = info.id();
            this.verifier = new WebhookSignatureVerifier(info.signingSecret());
            System.out.println("[gemini] Registered webhook " + info.id() + " → " + webhookUrl);
        } catch (Exception e) {
            this.setupError = e.getMessage();
            System.err.println("[gemini] Webhook registration failed: " + e.getMessage());
        }
    }

    public boolean isReady() {
        return verifier != null;
    }

    public WebhookSignatureVerifier verifier() {
        if (verifier == null) {
            throw new IllegalStateException("Webhook is not registered. " +
                    (setupError != null ? "Setup error: " + setupError : "Set GEMINI_API_KEY and GEMINI_PUBLIC_URL."));
        }
        return verifier;
    }

    public String webhookId() { return webhookId; }
    public String setupError() { return setupError; }
}
