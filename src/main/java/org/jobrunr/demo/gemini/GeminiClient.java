package org.jobrunr.demo.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Thin HTTP client around the Gemini API: webhooks, file upload, batch jobs.
 *
 * The webhook endpoints live under /v1, the model and file APIs under /v1beta —
 * we accept the base host (without version) and add the version per call.
 */
@Service
public class GeminiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper;
    private final GeminiConfig config;

    public GeminiClient(ObjectMapper mapper, GeminiConfig config) {
        this.mapper = mapper;
        this.config = config;
    }

    // --- Webhooks API (/v1/webhooks) ---------------------------------------

    public record WebhookInfo(String id, String name, String uri, List<String> events, String signingSecret) {}

    public WebhookInfo createWebhook(String name, List<String> events, String uri) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        body.put("uri", uri);
        ArrayNode evts = body.putArray("subscribed_events");
        events.forEach(evts::add);

        JsonNode resp = post(beta("/webhooks"), body);
        return parseWebhook(resp);
    }

    public List<WebhookInfo> listWebhooks() {
        JsonNode resp = get(beta("/webhooks"));
        var hooks = new java.util.ArrayList<WebhookInfo>();
        for (JsonNode node : resp.path("webhooks")) {
            hooks.add(parseWebhook(node));
        }
        return hooks;
    }

    public void deleteWebhook(String id) {
        delete(beta("/webhooks/" + stripPrefix(id, "webhooks/")));
    }

    private WebhookInfo parseWebhook(JsonNode node) {
        var events = new java.util.ArrayList<String>();
        for (JsonNode e : node.path("subscribed_events")) events.add(e.asText());
        // signing secret is only returned on create, under "new_signing_secret"
        String secret = node.path("new_signing_secret").asText(null);
        return new WebhookInfo(
                node.path("id").asText(null),
                node.path("name").asText(null),
                node.path("uri").asText(null),
                events,
                secret);
    }

    // --- Files API (/v1beta/files) -----------------------------------------

    public record GeminiFile(String name, String uri, String mimeType, String state) {}

    /** Multipart upload of a small in-memory payload. Returns the file resource. */
    public GeminiFile uploadFile(byte[] content, String mimeType, String displayName) {
        try {
            String boundary = "----geminiboundary" + System.nanoTime();
            String metadata = mapper.writeValueAsString(
                    mapper.createObjectNode()
                            .set("file", mapper.createObjectNode().put("display_name", displayName)));

            byte[] body = buildMultipart(boundary, metadata, mimeType, content);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.apiBase().replace("/v1beta", "") + "/upload/v1beta/files?uploadType=multipart"))
                    .header("x-goog-api-key", config.apiKey())
                    .header("Content-Type", "multipart/related; boundary=" + boundary)
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("Gemini file upload failed (" + resp.statusCode() + "): " + resp.body());
            }
            JsonNode json = mapper.readTree(resp.body()).path("file");
            return new GeminiFile(
                    json.path("name").asText(),
                    json.path("uri").asText(),
                    json.path("mimeType").asText(mimeType),
                    json.path("state").asText("UNKNOWN"));
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Gemini file upload failed", e);
        }
    }

    private byte[] buildMultipart(String boundary, String metadataJson, String mimeType, byte[] content) {
        var out = new java.io.ByteArrayOutputStream();
        try {
            String pre1 = "--" + boundary + "\r\n" +
                    "Content-Type: application/json; charset=UTF-8\r\n\r\n" + metadataJson + "\r\n";
            String pre2 = "--" + boundary + "\r\n" +
                    "Content-Type: " + mimeType + "\r\n\r\n";
            String post = "\r\n--" + boundary + "--\r\n";
            out.write(pre1.getBytes(StandardCharsets.UTF_8));
            out.write(pre2.getBytes(StandardCharsets.UTF_8));
            out.write(content);
            out.write(post.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    /** Returns the file resource metadata (uri, downloadUri, state, etc). */
    public JsonNode getFile(String fileResourceName) {
        return get(beta("/" + stripLeadingSlash(fileResourceName)));
    }

    /**
     * Downloads a Gemini-hosted file by resource name (e.g. "files/abc").
     * Tries the :download endpoint first, then falls back to the `downloadUri`
     * field on the file resource (used for batch output files).
     */
    public String downloadFile(String fileResourceName) {
        String name = stripPrefix(fileResourceName, "files/");
        String url = config.apiBase().replace("/v1beta", "") + "/v1beta/files/" + name + ":download?alt=media";
        String body = doDownload(url);
        if (body != null && !looksLikeApiError(body)) return body;

        // Fallback: ask for the file resource and use its downloadUri/uri field.
        JsonNode meta = getFile(fileResourceName);
        for (String field : new String[]{"downloadUri", "uri"}) {
            String url2 = meta.path(field).asText("");
            if (url2.isBlank()) continue;
            if (!url2.contains("alt=media")) {
                url2 = url2 + (url2.contains("?") ? "&" : "?") + "alt=media";
            }
            body = doDownload(url2);
            if (body != null && !looksLikeApiError(body)) return body;
        }
        throw new RuntimeException("Gemini file download failed for " + fileResourceName + ": " + body);
    }

    private String doDownload(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("x-goog-api-key", config.apiKey())
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) return null;
            return resp.body();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static boolean looksLikeApiError(String body) {
        return body != null && body.length() < 4096 && body.contains("\"error\"") && body.contains("\"code\"");
    }

    // --- Batch API (/v1beta/models/{model}:batchGenerateContent) -----------

    public record BatchOp(String name, String state) {}

    /**
     * Creates a batch job that reads JSONL requests from an uploaded file.
     * Uses :batchGenerateContent (the method advertised in the model's
     * supportedGenerationMethods). Static webhooks registered for this project
     * fire on completion.
     */
    public BatchOp createBatchFromFile(String model, String fileName, String displayName) {
        ObjectNode batch = mapper.createObjectNode();
        batch.put("display_name", displayName);
        batch.set("input_config", mapper.createObjectNode().put("file_name", fileName));

        ObjectNode body = mapper.createObjectNode();
        body.set("batch", batch);

        JsonNode resp = post(beta("/models/" + model + ":batchGenerateContent"), body);
        // Returns either a Batch resource ("name": "batches/...") or an Operation
        // ("name": "operations/..."). Either way "name" is what the webhook references.
        String name = resp.path("name").asText("");
        String state = resp.path("state").asText(
                resp.path("metadata").path("state").asText("PENDING"));
        return new BatchOp(name, state);
    }

    public JsonNode getOperation(String operationName) {
        return get(absolute("/" + stripLeadingSlash(operationName)));
    }

    /** GET a batch resource by name (e.g. "batches/abc"). */
    public JsonNode getBatch(String batchName) {
        return get(beta("/" + stripLeadingSlash(batchName)));
    }

    // --- Veo video generation (predictLongRunning) -------------------------

    public record VideoOp(String name) {}

    public VideoOp createVideoOperation(String model, String prompt) {
        ObjectNode instance = mapper.createObjectNode().put("prompt", prompt);
        ObjectNode body = mapper.createObjectNode();
        body.putArray("instances").add(instance);
        body.set("parameters", mapper.createObjectNode());
        JsonNode resp = post(beta("/models/" + model + ":predictLongRunning"), body);
        return new VideoOp(resp.path("name").asText(""));
    }

    /** Download a Gemini-hosted file as raw bytes (used for video MP4s). */
    public byte[] downloadFileBytes(String fileResourceName) {
        String name = stripPrefix(fileResourceName, "files/");
        String url = config.apiBase().replace("/v1beta", "") + "/v1beta/files/" + name + ":download?alt=media";
        byte[] data = doDownloadBytes(url);
        if (data != null && data.length > 0 && !looksLikeApiError(new String(data, 0, Math.min(data.length, 512), java.nio.charset.StandardCharsets.UTF_8))) {
            return data;
        }
        JsonNode meta = getFile(fileResourceName);
        for (String field : new String[]{"downloadUri", "uri"}) {
            String url2 = meta.path(field).asText("");
            if (url2.isBlank()) continue;
            if (!url2.contains("alt=media")) {
                url2 = url2 + (url2.contains("?") ? "&" : "?") + "alt=media";
            }
            data = doDownloadBytes(url2);
            if (data != null && data.length > 0) return data;
        }
        throw new RuntimeException("Gemini file download failed for " + fileResourceName);
    }

    private byte[] doDownloadBytes(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("x-goog-api-key", config.apiKey())
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 400) return null;
            return resp.body();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    // --- Helpers ------------------------------------------------------------

    private JsonNode get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("x-goog-api-key", config.apiKey())
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("Gemini GET failed (" + resp.statusCode() + ") " + url + ": " + resp.body());
            }
            return mapper.readTree(resp.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Gemini GET failed: " + url, e);
        }
    }

    private JsonNode post(String url, JsonNode body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("x-goog-api-key", config.apiKey())
                    .header("Content-Type", "application/json")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("Gemini POST failed (" + resp.statusCode() + ") " + url + ": " + resp.body());
            }
            return mapper.readTree(resp.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Gemini POST failed: " + url, e);
        }
    }

    private void delete(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("x-goog-api-key", config.apiKey())
                    .timeout(TIMEOUT)
                    .DELETE()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("Gemini DELETE failed (" + resp.statusCode() + ") " + url + ": " + resp.body());
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Gemini DELETE failed: " + url, e);
        }
    }

    private String beta(String path) {
        String base = config.apiBase();
        if (!base.endsWith("/v1beta")) base = base + "/v1beta";
        return base + path;
    }

    private String absolute(String pathOrUrl) {
        if (pathOrUrl.startsWith("http")) return pathOrUrl;
        return config.apiBase().replace("/v1beta", "") + pathOrUrl;
    }

    private static String stripPrefix(String s, String prefix) {
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }

    private static String stripLeadingSlash(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }
}
