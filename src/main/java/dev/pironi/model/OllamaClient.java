package dev.pironi.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public final class OllamaClient implements ModelClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String model;
    private final Duration timeout;
    private final int contextSize;
    private final int maxOutputTokens;

    public OllamaClient(
            URI baseUri,
            String model,
            Duration timeout,
            int contextSize,
            int maxOutputTokens
    ) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper(),
                baseUri.resolve("/api/chat"),
                model,
                timeout,
                contextSize,
                maxOutputTokens
        );
    }

    OllamaClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI endpoint,
            String model,
            Duration timeout,
            int contextSize,
            int maxOutputTokens
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.model = model;
        this.timeout = timeout;
        this.contextSize = contextSize;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public ModelResponse chat(List<ChatMessage> messages) throws IOException, InterruptedException {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put("stream", false);
        payload.put("format", "json");
        payload.put("think", false);
        payload.put("keep_alive", "10m");
        payload.putObject("options")
                .put("temperature", 0)
                .put("num_ctx", contextSize)
                .put("num_predict", maxOutputTokens);

        ArrayNode messageArray = payload.putArray("messages");
        for (ChatMessage message : messages) {
            messageArray.addObject()
                    .put("role", message.role())
                    .put("content", message.content());
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Ollama returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode body = objectMapper.readTree(response.body());
        JsonNode message = body.path("message");
        if (!message.isObject()) {
            throw new IOException("Ollama response has no message object");
        }

        return new ModelResponse(
                message.path("content").asText(""),
                body.path("prompt_eval_count").asLong(0),
                body.path("eval_count").asLong(0),
                body.path("total_duration").asLong(0)
        );
    }
}
