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

/** Bounded HTTPS fetch tool that rejects local/private destinations and redirects. */
public final class HttpGetTool implements Tool {
    static final int MAX_BODY_BYTES = 64 * 1024;
    private final Fetcher fetcher;

    public HttpGetTool() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.fetcher = (uri, timeout) -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", "application/json, text/plain;q=0.9, */*;q=0.1")
                    .header("User-Agent", "Pironi/0.1")
                    .GET().build();
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream()
            );
            try (InputStream body = response.body()) {
                return new FetchResponse(
                        response.statusCode(),
                        body.readNBytes(MAX_BODY_BYTES + 1),
                        response.headers().firstValue("Location").orElse("")
                );
            }
        };
    }

    HttpGetTool(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override public String name() { return "http_get"; }

    @Override public String description() {
        return "Fetch current external information over HTTPS. Redirects, credentials, "
                + "localhost and private network destinations are blocked; responses are bounded.";
    }

    @Override public String argumentSchema() {
        return "{\"url\":\"https URL, required\",\"timeoutSeconds\":\"integer, optional, max 30\"}";
    }

    @Override public boolean mutating() { return false; }

    @Override public ToolResult execute(JsonNode arguments) {
        try {
            URI uri = URI.create(ToolArguments.requiredText(arguments, "url"));
            validate(uri);
            int seconds = ToolArguments.optionalPositiveInt(arguments, "timeoutSeconds", 15, 30);
            FetchResponse response = fetcher.fetch(uri, Duration.ofSeconds(seconds));
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
            return ToolResult.success("HTTP " + response.statusCode() + "\n"
                    + new String(response.body(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure("HTTP request failed: " + e.getMessage());
        }
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
        FetchResponse fetch(URI uri, Duration timeout) throws Exception;
    }

    record FetchResponse(int statusCode, byte[] body, String location) {
        FetchResponse(int statusCode, byte[] body) {
            this(statusCode, body, "");
        }

        FetchResponse {
            if (body == null) body = new byte[0];
            if (location == null) location = "";
        }
    }
}
