package org.jobrunr.demo.gemini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GeminiConfig {

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.public-url:}")
    private String publicUrl;

    @Value("${gemini.api-base}")
    private String apiBase;

    @Value("${gemini.model}")
    private String model;

    @Value("${gemini.video-model}")
    private String videoModel;

    @Value("${gemini.webhook.path}")
    private String webhookPath;

    @Value("${gemini.webhook.secret-file}")
    private String secretFile;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && publicUrl != null && !publicUrl.isBlank();
    }

    public String apiKey() { return apiKey; }
    public String publicUrl() { return publicUrl; }
    public String apiBase() { return apiBase; }
    public String model() { return model; }
    public String videoModel() { return videoModel; }
    public String webhookPath() { return webhookPath; }
    public String secretFile() { return secretFile; }

    public String webhookUrl() {
        String base = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        return base + webhookPath;
    }
}
