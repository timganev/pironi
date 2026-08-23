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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OpenAiCompatibleClient implements ModelClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final int maxOutputTokens;
    private final boolean deepSeekThinking;
    private volatile boolean jsonSchemaSupported;
    /** How far the retry may raise the output budget when reasoning ate all of it. */
    static final int MAX_OUTPUT_BUDGET = 32_768;
    /** Attempts at getting an answer out of the transport, before the protocol is even read. */
    private static final int MAX_TRANSPORT_ATTEMPTS = 4;
    private static final long RETRY_BACKOFF_MILLIS = 1_000;

    public OpenAiCompatibleClient(
            URI baseUri,
            String model,
            String apiKey,
            Duration timeout,
            int maxOutputTokens
    ) {
        this(
                defaultHttpClient(),
                new ObjectMapper(),
                chatCompletionsEndpoint(baseUri),
                model,
                apiKey,
                timeout,
                maxOutputTokens,
                false
        );
    }

    /** Pins HTTP/1.1. */
    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public static OpenAiCompatibleClient deepSeek(
            URI baseUri,
            String model,
            String apiKey,
            Duration timeout,
            int maxOutputTokens
    ) {
        return new OpenAiCompatibleClient(
                defaultHttpClient(),
                new ObjectMapper(),
                chatCompletionsEndpoint(baseUri),
                model,
                apiKey,
                timeout,
                maxOutputTokens,
                true
        );
    }

    private static URI chatCompletionsEndpoint(URI baseUri) {
        String base = baseUri.toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + "chat/completions");
    }

    OpenAiCompatibleClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI endpoint,
            String model,
            String apiKey,
            Duration timeout,
            int maxOutputTokens
    ) {
        this(
                httpClient,
                objectMapper,
                endpoint,
                model,
                apiKey,
                timeout,
                maxOutputTokens,
                false
        );
    }

    OpenAiCompatibleClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI endpoint,
            String model,
            String apiKey,
            Duration timeout,
            int maxOutputTokens,
            boolean deepSeekThinking
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.model = model;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.maxOutputTokens = maxOutputTokens;
        this.deepSeekThinking = deepSeekThinking;
        this.jsonSchemaSupported = !deepSeekThinking;
    }

    @Override
    public ModelResponse chat(List<ChatMessage> messages) throws IOException, InterruptedException {
        return chat(messages, true);
    }

    @Override
    public ModelResponse chatText(List<ChatMessage> messages) throws IOException, InterruptedException {
        return chat(messages, false);
    }

    private ModelResponse chat(List<ChatMessage> messages, boolean structured)
            throws IOException, InterruptedException {
        long startNanos = System.nanoTime();
        int maxAttempts = deepSeekThinking ? 3 : 1;
        int requestAttempts = 0;
        String fallbackFrom = "";
        long totalDurationNanos = 0;
        int outputBudget = maxOutputTokens;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            boolean plainRecovery = structured && deepSeekThinking && attempt == maxAttempts;
            String schemaFailure = null;
            TimedResponse timed = send(messages, structured && !plainRecovery, jsonSchemaSupported,
                    plainRecovery, outputBudget);
            requestAttempts++;
            totalDurationNanos += timed.durationNanos();
            HttpResponse<String> response = timed.response();
            if (structured && jsonSchemaSupported && schemaUnsupported(response)) {
                schemaFailure = "HTTP " + response.statusCode() + ": " + safeError(response.body());
                fallbackFrom = "json_schema";
                jsonSchemaSupported = false;
                timed = send(messages, true, false, false);
                requestAttempts++;
                totalDurationNanos += timed.durationNanos();
                response = timed.response();
            }

            if (structured && deepSeekThinking && !plainRecovery
                    && response.statusCode() / 100 != 2 && responseFormatUnsupported(response)) {
                fallbackFrom = schemaFailure == null ? "json_object" : "json_schema,json_object";
                plainRecovery = true;
                timed = send(messages, false, false, true);
                requestAttempts++;
                totalDurationNanos += timed.durationNanos();
                response = timed.response();
            }

            if (response.statusCode() / 100 != 2) {
                if (schemaFailure != null) {
                    throw new IOException(
                            "Provider rejected json_schema (" + schemaFailure
                                    + "); json_object fallback returned HTTP "
                                    + response.statusCode() + ": " + safeError(response.body())
                    );
                }
                throw new IOException(
                        "Provider returned HTTP " + response.statusCode() + ": "
                                + safeError(response.body())
                );
            }

            JsonNode body = objectMapper.readTree(response.body());
            JsonNode choice = body.path("choices").path(0);
            String finishReason = choice.path("finish_reason").asText("unknown");
            JsonNode message = choice.path("message");
            if (!message.isObject()) {
                throw new IOException("Provider response has no choices[0].message object");
            }
            String content = responseContent(message.get("content"));
            if (content.isBlank() && attempt < maxAttempts) {
                // A thinking model can spend its whole output budget reasoning and never begin the
                // answer; the response then carries reasoning_content, no content, and
                // finish_reason=length. Sending the identical request again does the identical
                // thing, three times, and reports a provider fault that is not one.
                if ("length".equals(finishReason)) {
                    outputBudget = Math.min(outputBudget * 2, MAX_OUTPUT_BUDGET);
                }
                continue;
            }
            if (content.isBlank()) {
                // "Empty content" on its own is a wall. Whether the model ran out of room, was
                // stopped, or answered in a field we did not read are three different problems
                // with three different fixes, and the response says which.
                throw new IOException(
                        "Provider returned empty assistant content after " + maxAttempts
                                + " attempts (finish_reason=" + finishReason
                                + ", promptTokens=" + body.path("usage").path("prompt_tokens").asLong(0)
                                + ", completionTokens="
                                + body.path("usage").path("completion_tokens").asLong(0)
                                + ", format=" + (plainRecovery ? "text"
                                        : jsonSchemaSupported ? "json_schema" : "json_object")
                                + ", messageFields=" + fieldNames(message)
                                + ", outputBudget=" + outputBudget + ")"
                );
            }
            JsonNode usage = body.path("usage");
            return new ModelResponse(
                    content,
                    usage.path("prompt_tokens").asLong(0),
                    usage.path("completion_tokens").asLong(0),
                    totalDurationNanos,
                    0,
                    finishReason,
                    structured && !plainRecovery
                            ? (jsonSchemaSupported ? "json_schema" : "json_object") : "text",
                    requestAttempts,
                    fallbackFrom,
                    schemaFailure == null ? "" : schemaFailure,
                    System.nanoTime() - startNanos
            );
        }
        throw new IOException("Provider response retry loop ended unexpectedly");
    }

    private TimedResponse send(
            List<ChatMessage> messages,
            boolean structured,
            boolean jsonSchema,
            boolean plainRecovery
    )
            throws IOException, InterruptedException {
        return send(messages, structured, jsonSchema, plainRecovery, maxOutputTokens);
    }

    private TimedResponse send(
            List<ChatMessage> messages,
            boolean structured,
            boolean jsonSchema,
            boolean plainRecovery,
            int outputBudget
    )
            throws IOException, InterruptedException {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put("stream", false);
        payload.put("temperature", 0);
        payload.put("max_tokens", outputBudget);
        if (structured && jsonSchema) {
            payload.set("response_format", AgentResponseSchema.openAiResponseFormat(objectMapper));
        } else if (structured) {
            payload.putObject("response_format").put("type", "json_object");
        }
        if (deepSeekThinking) {
            payload.putObject("thinking").put("type", "enabled");
            payload.put("reasoning_effort", "high");
        }

        ArrayNode messageArray = payload.putArray("messages");
        for (ChatMessage message : messages) {
            messageArray.addObject()
                    .put("role", message.role())
                    .put("content", message.content());
        }
        if (plainRecovery) {
            messageArray.addObject()
                    .put("role", "user")
                    .put("content", "The provider returned empty structured responses. Reply now with "
                            + "one compact valid JSON object containing thought, toolCalls, and finalAnswer. "
                            + "Do not use Markdown fences.");
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        long started = System.nanoTime();
        HttpResponse<String> response = sendWithRetry(request, started);
        return new TimedResponse(response, System.nanoTime() - started);
    }

    /**
     * A rate limit or a bad gateway is not an answer, and this had no retry at all: one 429 from
     * OpenRouter ended the turn, while the same failure against Ollama was retried four times.
     * Nothing has been streamed at this point, so sending the request again is safe.
     *
     * <p>Only 429 and 5xx are retried. A 400 or 422 is the provider saying it dislikes the request
     * itself, which is how the structured-output fallback finds out to stop asking for a schema -
     * repeating it would only spend the same rejection three more times.
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request, long startNanos)
            throws IOException, InterruptedException {
        long backoffMillis = RETRY_BACKOFF_MILLIS;
        for (int attempt = 1; ; attempt++) {
            boolean lastAttempt = attempt >= MAX_TRANSPORT_ATTEMPTS;
            try {
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (!retryable(response.statusCode()) || lastAttempt) return response;
            } catch (IOException e) {
                if (lastAttempt) {
                    throw new IOException(
                            "Provider request failed after " + attempt + " attempts over "
                                    + ((System.nanoTime() - startNanos) / 1_000_000_000L)
                                    + "s: " + e.getMessage(), e);
                }
            }
            Thread.sleep(backoffMillis);
            backoffMillis *= 2;
        }
    }

    /** Transient on the server side: worth sending the same request again. */
    private static boolean retryable(int statusCode) {
        return statusCode / 100 == 5 || statusCode == 429;
    }

    private record TimedResponse(HttpResponse<String> response, long durationNanos) {
    }

    private static boolean schemaUnsupported(HttpResponse<String> response) {
        if (response.statusCode() != 400 && response.statusCode() != 422) return false;
        String body = response.body() == null ? "" : response.body().toLowerCase(Locale.ROOT);
        boolean namesSchema = body.contains("json_schema")
                || (body.contains("response_format") && body.contains("schema"))
                || (body.contains("response_format") && body.contains("type"))
                || body.contains("structured output");
        boolean saysUnsupported = body.contains("unsupported")
                || body.contains("not supported")
                || body.contains("does not support")
                || body.contains("unknown type")
                || body.contains("invalid")
                || body.contains("unavailable")
                || body.contains("must be one of")
                || body.contains("not allowed");
        return namesSchema && saysUnsupported;
    }

    private static boolean responseFormatUnsupported(HttpResponse<String> response) {
        if (response.statusCode() != 400 && response.statusCode() != 422) return false;
        String body = response.body() == null ? "" : response.body().toLowerCase(Locale.ROOT);
        return body.contains("response_format") && (body.contains("unsupported")
                || body.contains("not supported") || body.contains("unavailable")
                || body.contains("prompt must contain") || body.contains("not allowed"));
    }

    private static String responseContent(JsonNode content) throws IOException {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content != null && content.isTextual()) {
            return content.textValue();
        }
        if (content != null && content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode block : content) {
                String type = block.path("type").asText("");
                if (type.equals("output") || type.equals("text")) {
                    text.append(block.path("text").asText(""));
                }
            }
            return text.toString();
        }
        throw new IOException("Provider assistant content is neither text nor typed blocks");
    }

    private static String safeError(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 2_000 ? body : body.substring(0, 2_000) + "[truncated]";
    }

    /** Which keys the message actually carried, so "empty" can be told from "somewhere else". */
    static String fieldNames(JsonNode message) {
        if (message == null || !message.isObject()) return "none";
        List<String> names = new ArrayList<>();
        message.fieldNames().forEachRemaining(names::add);
        return names.isEmpty() ? "none" : String.join(",", names);
    }
}
