package org.jobrunr.demo.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Persists the registered webhook id + signing secret to a local JSON file. */
public class WebhookSecretStore {

    public record StoredWebhook(String id, String uri, String signingSecret, List<String> events) {}

    private final Path path;
    private final ObjectMapper mapper;

    public WebhookSecretStore(Path path, ObjectMapper mapper) {
        this.path = path;
        this.mapper = mapper;
    }

    public Optional<StoredWebhook> load() {
        if (!Files.exists(path)) return Optional.empty();
        try {
            JsonNode json = mapper.readTree(Files.readAllBytes(path));
            List<String> events = new ArrayList<>();
            for (JsonNode e : json.path("events")) events.add(e.asText());
            return Optional.of(new StoredWebhook(
                    json.path("id").asText(),
                    json.path("uri").asText(),
                    json.path("signingSecret").asText(),
                    events));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read webhook secret file: " + path, e);
        }
    }

    public void save(StoredWebhook hook) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", hook.id());
            node.put("uri", hook.uri());
            node.put("signingSecret", hook.signingSecret());
            ArrayNode evts = node.putArray("events");
            for (String e : hook.events()) evts.add(e);
            Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            try {
                Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX filesystem; skip
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write webhook secret file: " + path, e);
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
