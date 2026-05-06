package org.jobrunr.demo.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jobrunr.scheduling.BackgroundJob;
import org.jobrunr.server.runner.ThreadLocalJobContext;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.jobrunr.scheduling.JobBuilder.anExternalJob;

/**
 * Async text generation via Gemini Batch API, tracked as External Jobs.
 *
 * Flow:
 *   1. User submits a prompt → External Job created
 *   2. Trigger uploads a one-line JSONL file and creates a Gemini batch job
 *   3. The External Job parks in PROCESSED, waiting for Google
 *   4. Gemini POSTs a signed webhook → GeminiWebhookController signals success
 */
@Service
public class GeminiBatchService {

    private final GeminiClient client;
    private final GeminiConfig config;
    private final ObjectMapper mapper;

    private final Map<UUID, GeminiJob> activeJobs = new ConcurrentHashMap<>();
    private final Map<String, UUID> batchToJob = new ConcurrentHashMap<>();
    private final List<GeminiJob> completedJobs = new ArrayList<>();

    public GeminiBatchService(GeminiClient client, GeminiConfig config, ObjectMapper mapper) {
        this.client = client;
        this.config = config;
        this.mapper = mapper;
    }

    public GeminiJob launch(String prompt) {
        var jobId = BackgroundJob.create(anExternalJob()
                .withName("Gemini: " + truncate(prompt, 50))
                .withLabels("gemini", "webhook")
                .withQueue("high-prio")
                .withAmountOfRetries(0)
                .withDetails(() -> triggerBatch(prompt)));

        UUID jobKey = jobId.asUUID();
        var job = new GeminiJob(jobKey, prompt, null, "submitting", null, null, Instant.now());
        activeJobs.put(jobKey, job);
        return job;
    }

    /** Called by JobRunr when the External Job is picked up by a worker. */
    public void triggerBatch(String prompt) {
        var jobContext = ThreadLocalJobContext.getJobContext();
        UUID jobKey = jobContext.getJobId();

        try {
            String jsonl = buildSinglePromptJsonl(prompt, jobKey);
            String displayName = "ext-job-" + jobKey;
            GeminiClient.GeminiFile uploaded = client.uploadFile(
                    jsonl.getBytes(StandardCharsets.UTF_8),
                    "application/jsonl",
                    displayName + ".jsonl");

            GeminiClient.BatchOp op = client.createBatchFromFile(config.model(), uploaded.name(), displayName);

            String batchName = op.name();
            batchToJob.put(normalizeId(batchName), jobKey);
            activeJobs.computeIfPresent(jobKey, (k, j) -> j.withBatchName(batchName));
            System.out.println("[gemini] Submitted batch " + batchName + " for job " + jobKey);
        } catch (Exception e) {
            System.err.println("[gemini] batch submit failed: " + e.getMessage());
            // Re-throw so JobRunr fails the job. We cannot signal here (job is still PROCESSING).
            throw e;
        }
    }

    /** Called by GeminiWebhookController when "batch.succeeded" arrives. */
    public void onBatchSucceeded(String batchId) {
        UUID jobKey = batchToJob.remove(normalizeId(batchId));
        if (jobKey == null) {
            System.err.println("[gemini] webhook batch.succeeded for unknown batch " + batchId);
            return;
        }
        try {
            JsonNode batch = client.getBatch(batchId);
            String outputFile = findOutputFileName(batch);
            if (outputFile == null) {
                throw new IllegalStateException("Could not locate output file in batch resource: " + batch);
            }
            String text = extractFirstResponseText(outputFile);
            BackgroundJob.signalExternalJobSucceeded(jobKey, "Gemini batch completed");
            GeminiJob done = activeJobs.remove(jobKey);
            if (done != null) completedJobs.addFirst(done.withResult(text));
        } catch (Exception e) {
            BackgroundJob.signalExternalJobFailed(jobKey, "Failed to read Gemini output: " + e.getMessage());
            GeminiJob done = activeJobs.remove(jobKey);
            if (done != null) completedJobs.addFirst(done.withError(e.getMessage()));
        }
    }

    /** Walks the common shapes of Gemini batch responses to find a "files/..." reference. */
    private static String findOutputFileName(JsonNode batch) {
        // Known shapes:
        //   batch.output.file_name
        //   batch.output.responses_file
        //   batch.response.responsesFile
        //   batch.metadata.output.file_name
        for (String[] path : new String[][]{
                {"output", "file_name"},
                {"output", "responses_file"},
                {"output", "responsesFile"},
                {"response", "responsesFile"},
                {"response", "responses_file"},
                {"metadata", "output", "file_name"}}) {
            JsonNode n = batch;
            for (String p : path) n = n.path(p);
            String v = n.asText("");
            if (!v.isBlank()) return v;
        }
        return null;
    }

    /** Called by GeminiWebhookController when "batch.failed" / "batch.cancelled" / "batch.expired" arrive. */
    public void onBatchFailed(String batchId, String reason) {
        UUID jobKey = batchToJob.remove(normalizeId(batchId));
        if (jobKey == null) return;
        BackgroundJob.signalExternalJobFailed(jobKey, "Gemini reported: " + reason);
        GeminiJob done = activeJobs.remove(jobKey);
        if (done != null) completedJobs.addFirst(done.withError(reason));
    }

    public Collection<GeminiJob> getActiveJobs() { return activeJobs.values(); }
    public List<GeminiJob> getCompletedJobs() { return completedJobs; }

    // --- internals ----------------------------------------------------------

    private String buildSinglePromptJsonl(String prompt, UUID key) {
        try {
            ObjectNode line = mapper.createObjectNode();
            line.put("key", key.toString());
            ObjectNode request = mapper.createObjectNode();
            ArrayNode contents = request.putArray("contents");
            ObjectNode content = contents.addObject();
            content.put("role", "user");
            ArrayNode parts = content.putArray("parts");
            parts.addObject().put("text", prompt);
            line.set("request", request);
            return mapper.writeValueAsString(line) + "\n";
        } catch (Exception e) {
            throw new RuntimeException("Failed to build batch JSONL", e);
        }
    }

    private String extractFirstResponseText(String outputFile) {
        if (outputFile == null || outputFile.isBlank()) {
            throw new IllegalArgumentException("output file missing");
        }
        if (!outputFile.startsWith("files/")) {
            throw new IllegalArgumentException("Unexpected output file: " + outputFile);
        }
        String body = client.downloadFile(outputFile);

        // Try line-by-line JSONL first.
        for (String line : body.split("\n")) {
            if (line.isBlank()) continue;
            JsonNode node;
            try {
                node = mapper.readTree(line);
            } catch (Exception e) {
                System.err.println("[gemini] non-JSON line, skipping: " + line);
                continue;
            }
            String text = findText(node);
            if (text != null) return text;
        }
        // Fall back to parsing the full body as a single JSON document.
        try {
            String text = findText(mapper.readTree(body));
            if (text != null) return text;
        } catch (Exception ignored) {}
        throw new IllegalStateException("Could not find generated text in output");
    }

    /** Walk likely paths to a text candidate part. Returns null if not found. */
    private static String findText(JsonNode node) {
        for (String[] path : new String[][]{
                {"response", "candidates"},
                {"response", "response", "candidates"},
                {"candidates"}}) {
            JsonNode n = node;
            for (String p : path) n = n.path(p);
            if (n.isArray() && !n.isEmpty()) {
                JsonNode parts = n.get(0).path("content").path("parts");
                if (parts.isArray() && !parts.isEmpty()) {
                    String t = parts.get(0).path("text").asText("");
                    if (!t.isBlank()) return t;
                }
            }
        }
        return null;
    }

    /** Webhooks may report the short id or the full resource name; key both maps the same way. */
    static String normalizeId(String id) {
        if (id == null) return null;
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
