package org.jobrunr.demo.gemini;

import java.time.Instant;
import java.util.UUID;

public record GeminiVideoJob(
        UUID jobKey,
        String prompt,
        String operationName,
        String status,
        String videoUrl,
        String error,
        Instant createdAt) {

    public GeminiVideoJob withOperation(String name) {
        return new GeminiVideoJob(jobKey, prompt, name, "submitted", videoUrl, error, createdAt);
    }

    public GeminiVideoJob withVideoUrl(String url) {
        return new GeminiVideoJob(jobKey, prompt, operationName, "succeeded", url, null, createdAt);
    }

    public GeminiVideoJob withError(String err) {
        return new GeminiVideoJob(jobKey, prompt, operationName, "failed", null, err, createdAt);
    }
}
