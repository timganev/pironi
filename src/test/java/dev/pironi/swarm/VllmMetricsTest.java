package dev.pironi.swarm;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VllmMetricsTest {
    private static final String SCRAPE_1 = """
            # HELP vllm:kv_cache_usage_perc KV-cache usage.
            # TYPE vllm:kv_cache_usage_perc gauge
            vllm:kv_cache_usage_perc{engine="0",model_name="qwen36"} 0.25
            vllm:num_requests_waiting{engine="0",model_name="qwen36"} 1.0
            vllm:num_preemptions_total{engine="0",model_name="qwen36"} 4.0
            vllm:prefix_cache_queries_total{engine="0",model_name="qwen36"} 10000.0
            vllm:prefix_cache_hits_total{engine="0",model_name="qwen36"} 8000.0
            vllm:request_queue_time_seconds_sum{engine="0",model_name="qwen36"} 1.0
            vllm:request_queue_time_seconds_count{engine="0",model_name="qwen36"} 10.0
            vllm:request_queue_time_seconds_bucket{engine="0",le="0.3",model_name="qwen36"} 9.0
            vllm:request_prefill_time_seconds_sum{engine="0",model_name="qwen36"} 2.0
            vllm:prompt_tokens_total{engine="0",model_name="qwen36"} 50000.0
            vllm:prompt_tokens_cached_total{engine="0",model_name="qwen36"} 30000.0
            vllm:num_preemptions_created{engine="0",model_name="qwen36"} 1.79e9
            """;
    private static final String SCRAPE_2 = """
            vllm:kv_cache_usage_perc{engine="0",model_name="qwen36"} 0.5
            vllm:num_requests_waiting{engine="0",model_name="qwen36"} 0.0
            vllm:num_preemptions_total{engine="0",model_name="qwen36"} 6.0
            vllm:prefix_cache_queries_total{engine="0",model_name="qwen36"} 20000.0
            vllm:prefix_cache_hits_total{engine="0",model_name="qwen36"} 14000.0
            vllm:request_queue_time_seconds_sum{engine="0",model_name="qwen36"} 4.0
            vllm:request_queue_time_seconds_count{engine="0",model_name="qwen36"} 12.0
            vllm:request_prefill_time_seconds_sum{engine="0",model_name="qwen36"} 3.0
            vllm:prompt_tokens_total{engine="0",model_name="qwen36"} 70000.0
            vllm:prompt_tokens_cached_total{engine="0",model_name="qwen36"} 42000.0
            """;

    @Test
    void scrapesAreTurnedIntoRatesOverTheInterval() {
        Sensors s = VllmMetrics.sensors(VllmMetrics.parse(SCRAPE_1), VllmMetrics.parse(SCRAPE_2), false);
        assertTrue(s.available());
        assertEquals(0.5, s.kvUsage());
        assertEquals(0.0, s.waiting());
        assertEquals(2.0, s.preemptions());
        assertEquals(1.5, s.queueSeconds(), 1e-9);   // 3 s over 2 requests
        assertEquals(0.6, s.prefixHitRate(), 1e-9);  // 6k of 10k
        assertEquals(8_000, s.prefillRate(), 1e-9);  // 8k uncached in 1 s of prefill
    }

    @Test
    void theFirstScrapeReportsGaugesOnly() {
        Sensors s = VllmMetrics.sensors(null, VllmMetrics.parse(SCRAPE_1), false);
        assertEquals(0.25, s.kvUsage());
        assertEquals(0.0, s.preemptions());
        assertTrue(Double.isNaN(s.queueSeconds()));
    }

    @Test
    void bucketsAndCreationTimesAreLeftOut() {
        Map<String, Double> m = VllmMetrics.parse(SCRAPE_1);
        assertTrue(m.keySet().stream().noneMatch(k -> k.endsWith("_bucket") || k.endsWith("_created")));
    }

    @Test
    void theMetricsPathSitsBesideTheApi() {
        assertEquals(URI.create("http://127.0.0.1:8000/metrics"),
                VllmMetrics.metricsUri(URI.create("http://127.0.0.1:8000/v1")));
        assertEquals(URI.create("http://127.0.0.1:8000/metrics"),
                VllmMetrics.metricsUri(URI.create("http://127.0.0.1:8000/v1/")));
    }
}
