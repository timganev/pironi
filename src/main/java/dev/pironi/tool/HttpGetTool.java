package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Bounded HTTPS fetch tool that rejects local/private destinations and redirects. */
public final class HttpGetTool implements Tool {
    static final int MAX_BODY_BYTES = 64 * 1024;
    static final int MAX_HTML_BYTES = 8 * 1024;
    private static final String[] BLOCKED_HEADERS = {
            "host", "content-length", "transfer-encoding", "connection"
    };
    private final Fetcher fetcher;
    private final HeaderResolver headerResolver;

    public HttpGetTool() {
        this(HeaderResolver.EMPTY);
    }

    public HttpGetTool(HeaderResolver headerResolver) {
        this.headerResolver = headerResolver == null ? HeaderResolver.EMPTY : headerResolver;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.fetcher = (uri, timeout, headers) -> {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", "application/json, text/plain;q=0.9, */*;q=0.1")
                    .header("User-Agent", "Pironi/0.1");
            if (headers != null) {
                headers.forEach(requestBuilder::header);
            }
            HttpRequest request = requestBuilder.GET().build();
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream()
            );
            try (InputStream body = response.body()) {
                return new FetchResponse(
                        response.statusCode(),
                        body.readNBytes(MAX_BODY_BYTES + 1),
                        response.headers().firstValue("Location").orElse(""),
                        response.headers().firstValue("Content-Type").orElse("")
                );
            }
        };
    }

    HttpGetTool(Fetcher fetcher) {
        this(HeaderResolver.EMPTY, fetcher);
    }

    /** Test seam: injects a fake fetcher while exercising the real header resolution. */
    HttpGetTool(HeaderResolver headerResolver, Fetcher fetcher) {
        this.headerResolver = headerResolver == null ? HeaderResolver.EMPTY : headerResolver;
        this.fetcher = fetcher;
    }

    @Override public String name() { return "http_get"; }

    @Override public String description() {
        return "Fetch current external information over HTTPS. Redirects, credentials, "
                + "localhost and private network destinations are blocked; responses are bounded. "
                + "Optional headers object is supported; Authorization headers are resolved from "
                + "Pironi-managed placeholders (e.g. \"Bearer PIRONI_API_KEY\") and only for trusted "
                + "API hosts.";
    }

    @Override public String argumentSchema() {
        return "{\"url\":\"https URL, required\",\"timeoutSeconds\":\"integer, optional, max 30\","
                + "\"headers\":\"object, optional, e.g. {\\\"Authorization\\\":\\\"Bearer PIRONI_API_KEY\\\"}\"}";
    }

    @Override public boolean mutating() { return false; }

    @Override public ToolResult execute(JsonNode arguments) {
        try {
            URI uri = URI.create(ToolArguments.requiredText(arguments, "url"));
            validate(uri);
            int seconds = ToolArguments.optionalPositiveInt(arguments, "timeoutSeconds", 15, 30);
            Map<String, String> resolvedHeaders = resolveHeaders(uri, arguments);
            FetchResponse response = fetcher.fetch(
                    uri, Duration.ofSeconds(seconds), resolvedHeaders);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String redirect = response.statusCode() >= 300 && response.statusCode() < 400
                        ? "; redirect blocked"
                                + (response.location().isBlank() ? ""
                                : "; Location: " + truncate(response.location(), 1_000))
                        : "";
                return ToolResult.failure("HTTP " + response.statusCode() + redirect);
            }
            if (response.body().length > MAX_BODY_BYTES) {
                return ToolResult.failure("HTTP response exceeds " + MAX_BODY_BYTES + " bytes");
            }
            byte[] body = response.body();
            boolean htmlTruncated = response.contentType().toLowerCase().contains("text/html")
                    && body.length > MAX_HTML_BYTES;
            int visibleBytes = htmlTruncated ? MAX_HTML_BYTES : body.length;
            String headerNote = resolvedHeaders.isEmpty() ? ""
                    : "[" + resolvedHeaders.size() + " headers applied]\n";
            return ToolResult.success(headerNote + "HTTP " + response.statusCode() + "\n"
                    + new String(body, 0, visibleBytes, StandardCharsets.UTF_8)
                    + (htmlTruncated ? "\n[HTML excerpt truncated after " + MAX_HTML_BYTES
                    + " bytes; use a compact data endpoint]" : ""));
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure("HTTP request failed: " + e.getMessage());
        }
    }

    /**
     * Resolves the optional {@code headers} object against the header resolver. Every resolved
     * value is applied to the outgoing request but never echoed into the {@code ToolResult};
     * only the count is reported, so a substituted secret cannot leak into the trace or prompt.
     */
    private Map<String, String> resolveHeaders(URI uri, JsonNode arguments) {
        JsonNode headers = ToolArguments.optionalObject(arguments, "headers");
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        List<String> blocked = new java.util.ArrayList<>();
        Map<String, String> result = new java.util.LinkedHashMap<>();
        String host = uri.getHost() == null ? "" : uri.getHost();
        var fields = headers.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String name = entry.getKey().trim();
            JsonNode valueNode = entry.getValue();
            if (name.isEmpty() || !valueNode.isTextual()) {
                throw new IllegalArgumentException(
                        "headers must map non-blank header names to string values");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (List.of(BLOCKED_HEADERS).contains(lower)) {
                blocked.add(lower);
                continue;
            }
            java.util.Optional<String> resolved = headerResolver.resolve(host, name, valueNode.textValue());
            if (resolved.isEmpty()) {
                throw new IllegalArgumentException(
                        "header '" + name + "' is not permitted for host '" + host
                                + "'; Authorization headers require a placeholder on a trusted API host");
            }
            result.put(name, resolved.get());
        }
        if (!blocked.isEmpty()) {
            throw new IllegalArgumentException("header names are not allowed: " + blocked);
        }
        return result;
    }

    private static void validate(URI uri) throws Exception {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only HTTPS URLs are allowed");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("URL must include a host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("URL credentials are not allowed");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress() || uniqueLocalIpv6(address)) {
                throw new IllegalArgumentException("Local and private network destinations are blocked");
            }
        }
    }

    private static boolean uniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    @FunctionalInterface
    interface Fetcher {
        FetchResponse fetch(URI uri, Duration timeout, Map<String, String> headers) throws Exception;
    }

    record FetchResponse(int statusCode, byte[] body, String location, String contentType) {
        FetchResponse(int statusCode, byte[] body) {
            this(statusCode, body, "", "");
        }

        FetchResponse(int statusCode, byte[] body, String location) {
            this(statusCode, body, location, "");
        }

        FetchResponse {
            if (body == null) body = new byte[0];
            if (location == null) location = "";
            if (contentType == null) contentType = "";
        }
    }
}
