package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/** Bounded, read-only download throughput measurement without shell access. */
public final class NetworkSpeedTool implements Tool {
    static final int DEFAULT_MEGABYTES = 10;
    static final int MAX_MEGABYTES = 25;
    private final Probe probe;

    public NetworkSpeedTool() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.probe = bytes -> {
            URI uri = URI.create("https://speed.cloudflare.com/__down?bytes=" + bytes);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(45))
                    .header("User-Agent", "Pironi/0.1")
                    .GET().build();
            long started = System.nanoTime();
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream()
            );
            long firstByte;
            long count = 0;
            try (InputStream input = response.body()) {
                int first = input.read();
                firstByte = System.nanoTime();
                if (first >= 0) count = 1;
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    count += read;
                    if (count > bytes) throw new IllegalStateException("Probe exceeded byte limit");
                }
            }
            return new ProbeResult(response.statusCode(), count, firstByte - started,
                    System.nanoTime() - started);
        };
    }

    NetworkSpeedTool(Probe probe) { this.probe = probe; }

    @Override public String name() { return "network_speed"; }

    @Override public String description() {
        return "Measure current internet latency and bounded download throughput in Mbps. "
                + "Use this instead of http_get or shell speed-test commands.";
    }

    @Override public String argumentSchema() {
        return "{\"megabytes\":\"integer, optional, 1..25; default 10\"}";
    }

    @Override public boolean mutating() { return false; }

    @Override public ToolResult validate(JsonNode arguments) {
        try {
            megabytes(arguments);
            return ToolResult.success("validated");
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e);
        }
    }

    @Override public ToolResult execute(JsonNode arguments) {
        try {
            int megabytes = megabytes(arguments);
            long requestedBytes = megabytes * 1_000_000L;
            ProbeResult result = probe.run(requestedBytes);
            if (result.statusCode() < 200 || result.statusCode() >= 300) {
                return ToolResult.failure("Speed probe returned HTTP " + result.statusCode());
            }
            if (result.bytes() <= 0 || result.bytes() > requestedBytes) {
                return ToolResult.failure("Speed probe returned an invalid byte count");
            }
            double seconds = result.durationNanos() / 1_000_000_000.0;
            double mbps = result.bytes() * 8.0 / seconds / 1_000_000.0;
            return ToolResult.success(String.format(Locale.ROOT,
                    "downloadMbps=%.2f\nlatencyMs=%.1f\nbytes=%d\ndurationMs=%.0f\n"
                            + "latencyMethod=HTTPS time to first byte\n"
                            + "method=bounded Cloudflare download sample",
                    mbps, result.firstByteNanos() / 1_000_000.0, result.bytes(),
                    result.durationNanos() / 1_000_000.0));
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e);
        } catch (Exception e) {
            return ToolResult.failure("Network speed probe failed: " + e.getMessage());
        }
    }

    private static int megabytes(JsonNode arguments) {
        return ToolArguments.optionalPositiveInt(
                arguments, "megabytes", DEFAULT_MEGABYTES, MAX_MEGABYTES
        );
    }

    @FunctionalInterface interface Probe { ProbeResult run(long bytes) throws Exception; }
    record ProbeResult(int statusCode, long bytes, long firstByteNanos, long durationNanos) {}
}
