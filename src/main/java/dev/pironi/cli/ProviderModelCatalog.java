package dev.pironi.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.model.ProviderType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class ProviderModelCatalog {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    ProviderModelCatalog(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    List<String> models(CliOptions options) throws IOException {
        return switch (options.provider()) {
            case OLLAMA -> ollamaModels(options.baseUri(), options.model());
            case DEEPSEEK -> openAiModels(
                    options.baseUri().resolve("/models"), options.apiKey(), options.model()
            );
            case OPENROUTER -> openAiModels(
                    options.baseUri().resolve("models"), options.apiKey(), options.model()
            );
            case OPENAI_COMPATIBLE -> List.of(options.model());
        };
    }

    private List<String> ollamaModels(URI baseUri, String currentModel) throws IOException {
        URI endpoint = URI.create(baseUri.getScheme() + "://" + baseUri.getAuthority() + "/api/tags");
        JsonNode body = get(endpoint, null);
        List<String> models = new ArrayList<>();
        for (JsonNode model : body.path("models")) {
            String name = model.path("name").asText("");
            if (!name.isBlank()) models.add(name);
        }
        return withCurrent(models, currentModel);
    }

    private List<String> openAiModels(URI endpoint, String apiKey, String currentModel)
            throws IOException {
        JsonNode body = get(endpoint, apiKey);
        List<String> models = new ArrayList<>();
        for (JsonNode model : body.path("data")) {
            String id = model.path("id").asText("");
            if (!id.isBlank()) models.add(id);
        }
        models.sort(String::compareToIgnoreCase);
        return withCurrent(models, currentModel);
    }

    private JsonNode get(URI endpoint, String apiKey) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(15))
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading model catalog", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Model catalog returned HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private static List<String> withCurrent(List<String> models, String currentModel) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (currentModel != null && !currentModel.isBlank()) result.add(currentModel);
        result.addAll(models);
        return List.copyOf(result);
    }
}
