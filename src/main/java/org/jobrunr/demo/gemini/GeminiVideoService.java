package org.jobrunr.demo.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import org.jobrunr.scheduling.BackgroundJob;
import org.jobrunr.server.runner.ThreadLocalJobContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.jobrunr.scheduling.JobBuilder.anExternalJob;

/**
 * Veo video generation via Gemini's predictLongRunning, completion delivered
 * via the static webhook (event = video.generated). Same External Job pattern
 * as the GPU/Replicate demo, except Google pushes us instead of us polling.
 */
@Service
public class GeminiVideoService {

    private final GeminiClient client;
    private final GeminiConfig config;

    private final Map<UUID, GeminiVideoJob> activeJobs = new ConcurrentHashMap<>();
    private final Map<String, UUID> opToJob = new ConcurrentHashMap<>();
    /** Maps short id (UUID) → cached MP4 bytes, served via /gemini-video/file/{uuid}.mp4 */
    private final Map<UUID, byte[]> videoCache = new ConcurrentHashMap<>();
    private final List<GeminiVideoJob> completedJobs = new ArrayList<>();

    public GeminiVideoService(GeminiClient client, GeminiConfig config) {
        this.client = client;
        this.config = config;
    }

    public GeminiVideoJob launch(String prompt) {
        var jobId = BackgroundJob.create(anExternalJob()
                .withName("Gemini Veo: " + truncate(prompt, 50))
                .withLabels("gemini", "veo", "webhook")
                .withQueue("high-prio")
                .withAmountOfRetries(0)
                .withDetails(() -> triggerVideoGeneration(prompt)));

        UUID jobKey = jobId.asUUID();
        var job = new GeminiVideoJob(jobKey, prompt, null, "submitting", null, null, Instant.now());
        activeJobs.put(jobKey, job);
        return job;
    }

    public void triggerVideoGeneration(String prompt) {
        var jobContext = ThreadLocalJobContext.getJobContext();
        UUID jobKey = jobContext.getJobId();
        try {
            GeminiClient.VideoOp op = client.createVideoOperation(config.videoModel(), prompt);
            String opName = op.name();
            opToJob.put(normalizeId(opName), jobKey);
            activeJobs.computeIfPresent(jobKey, (k, j) -> j.withOperation(opName));
            System.out.println("[gemini] Submitted video op " + opName + " for job " + jobKey);
        } catch (Exception e) {
            System.err.println("[gemini] video submit failed: " + e.getMessage());
            activeJobs.computeIfPresent(jobKey, (k, j) -> j.withError(e.getMessage()));
            // Re-throw so JobRunr fails the job normally; we cannot call
            // signalExternalJobFailed here (job is still PROCESSING, not PROCESSED).
            throw e;
        }
    }

    public void onVideoGenerated(String operationId, String fileFromPayload) {
        UUID jobKey = opToJob.remove(normalizeId(operationId));
        if (jobKey == null) {
            System.err.println("[gemini] video.generated for unknown op " + operationId);
            return;
        }
        try {
            String fileName = (fileFromPayload != null && fileFromPayload.startsWith("files/"))
                    ? fileFromPayload
                    : findFileFromOperation(operationId);
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalStateException("No video file found in operation " + operationId);
            }
            byte[] mp4 = client.downloadFileBytes(fileName);
            videoCache.put(jobKey, mp4);
            persistOptional(jobKey, mp4);

            BackgroundJob.signalExternalJobSucceeded(jobKey, "Video generated (" + mp4.length + " bytes)");
            GeminiVideoJob done = activeJobs.remove(jobKey);
            if (done != null) completedJobs.addFirst(done.withVideoUrl("/gemini-video/file/" + jobKey + ".mp4"));
        } catch (Exception e) {
            BackgroundJob.signalExternalJobFailed(jobKey, "Failed to fetch video: " + e.getMessage());
            GeminiVideoJob done = activeJobs.remove(jobKey);
            if (done != null) completedJobs.addFirst(done.withError(e.getMessage()));
        }
    }

    public void onVideoFailed(String operationId, String reason) {
        UUID jobKey = opToJob.remove(normalizeId(operationId));
        if (jobKey == null) return;
        BackgroundJob.signalExternalJobFailed(jobKey, "Gemini reported: " + reason);
        GeminiVideoJob done = activeJobs.remove(jobKey);
        if (done != null) completedJobs.addFirst(done.withError(reason));
    }

    public byte[] getVideoBytes(UUID jobKey) {
        return videoCache.get(jobKey);
    }

    public Collection<GeminiVideoJob> getActiveJobs() { return activeJobs.values(); }
    public List<GeminiVideoJob> getCompletedJobs() { return completedJobs; }

    /** When the webhook only carries an op id, fetch the operation and dig for the video file. */
    private String findFileFromOperation(String operationId) {
        String name = operationId.startsWith("operations/") || operationId.contains("/operations/")
                ? operationId
                : "operations/" + operationId;
        try {
            JsonNode op = client.getOperation(name);
            // Common shapes:
            //   response.generatedSamples[0].video.uri  → http URL
            //   response.generatedVideos[0].video.uri
            //   response.predictions[0].videoUri / video.fileUri
            //   metadata.outputFile
            for (String[] path : new String[][]{
                    {"response", "generatedSamples", "0", "video", "uri"},
                    {"response", "generatedVideos", "0", "video", "uri"},
                    {"response", "predictions", "0", "video", "uri"},
                    {"response", "predictions", "0", "videoUri"},
                    {"response", "videoUri"},
                    {"response", "outputFile"},
                    {"metadata", "outputFile"}}) {
                JsonNode n = op;
                for (String p : path) {
                    if (p.matches("\\d+")) n = n.path(Integer.parseInt(p));
                    else n = n.path(p);
                }
                String v = n.asText("");
                if (!v.isBlank()) {
                    System.out.println("[gemini] resolved video file via " + String.join(".", path) + " = " + v);
                    return v;
                }
            }
            System.err.println("[gemini] could not find video in operation: " + op);
        } catch (Exception e) {
            System.err.println("[gemini] failed fetching operation " + name + ": " + e.getMessage());
        }
        return null;
    }

    /** Best-effort save to /tmp so the user can find the file outside the JVM. */
    private void persistOptional(UUID jobKey, byte[] mp4) {
        try {
            Path p = Path.of(System.getProperty("java.io.tmpdir"), "gemini-veo-" + jobKey + ".mp4");
            Files.write(p, mp4, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("[gemini] saved video to " + p);
        } catch (IOException ignored) {}
    }

    static String normalizeId(String id) {
        if (id == null) return null;
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
