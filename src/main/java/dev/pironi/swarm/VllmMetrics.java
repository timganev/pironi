package dev.pironi.swarm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads vLLM's Prometheus /metrics and turns two consecutive scrapes into {@link Sensors}.
 * Counters are summed over their labels; the interval's rates are differences of the sums.
 */
public final class VllmMetrics {
    private final HttpClient http;
    private final URI uri;
    private final String apiKey;
    private Map<String, Double> previous;

    public VllmMetrics(URI uri, String apiKey) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), uri, apiKey);
    }

    VllmMetrics(HttpClient http, URI uri, String apiKey) {
        this.http = http;
        this.uri = uri;
        this.apiKey = apiKey;
    }

    /** {@code http://host:8000/v1} → {@code http://host:8000/metrics}. */
    public static URI metricsUri(URI baseUri) {
        String base = baseUri.toString().replaceAll("/+$", "");
        if (base.endsWith("/v1")) base = base.substring(0, base.length() - 3);
        return URI.create(base + "/metrics");
    }

    /** Scrapes once; the first call only primes the counters and reports gauges alone. */
    public Sensors read(boolean rateLimited) {
        Map<String, Double> now;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET();
            if (apiKey != null && !apiKey.isBlank()) b.header("Authorization", "Bearer " + apiKey);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) return Sensors.unavailable(rateLimited);
            now = parse(r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Sensors.unavailable(rateLimited);
        } catch (Exception e) {
            return Sensors.unavailable(rateLimited);
        }
        if (!now.containsKey("vllm:kv_cache_usage_perc")) return Sensors.unavailable(rateLimited);
        Sensors s = sensors(previous, now, rateLimited);
        previous = now;
        return s;
    }

    static Sensors sensors(Map<String, Double> before, Map<String, Double> now, boolean rateLimited) {
        double kv = now.getOrDefault("vllm:kv_cache_usage_perc", Double.NaN);
        double waiting = now.getOrDefault("vllm:num_requests_waiting", Double.NaN);
        if (before == null) {
            return new Sensors(true, kv, waiting, 0, Double.NaN, Double.NaN, Double.NaN, rateLimited);
        }
        double preempt = delta(before, now, "vllm:num_preemptions_total");
        double qCount = delta(before, now, "vllm:request_queue_time_seconds_count");
        double queue = qCount > 0 ? delta(before, now, "vllm:request_queue_time_seconds_sum") / qCount : Double.NaN;
        double queries = delta(before, now, "vllm:prefix_cache_queries_total");
        double hit = queries >= 1_000 ? delta(before, now, "vllm:prefix_cache_hits_total") / queries : Double.NaN;
        double prefillSeconds = delta(before, now, "vllm:request_prefill_time_seconds_sum");
        double uncached = delta(before, now, "vllm:prompt_tokens_total")
                - delta(before, now, "vllm:prompt_tokens_cached_total");
        double rate = prefillSeconds > 0.05 && uncached > 500 ? uncached / prefillSeconds : Double.NaN;
        return new Sensors(true, kv, waiting, preempt, queue, hit, rate, rateLimited);
    }

    private static double delta(Map<String, Double> a, Map<String, Double> b, String k) {
        return Math.max(0, b.getOrDefault(k, 0.0) - a.getOrDefault(k, 0.0));
    }

    /** Sums each metric over its label sets; histogram buckets are left out. */
    static Map<String, Double> parse(String text) {
        Map<String, Double> m = new HashMap<>();
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            int brace = line.indexOf('{');
            int space = line.lastIndexOf(' ');
            if (space < 0) continue;
            String name = (brace >= 0 && brace < space ? line.substring(0, brace) : line.substring(0, space)).trim();
            if (name.endsWith("_bucket") || name.endsWith("_created")) continue;
            try {
                m.merge(name, Double.parseDouble(line.substring(space + 1).trim()), Double::sum);
            } catch (NumberFormatException ignored) {
                // a value Prometheus allows but we do not use (NaN text etc.)
            }
        }
        return m;
    }
}
