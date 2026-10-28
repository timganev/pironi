package dev.pironi.swarm;

/**
 * One reading of the server's load, already turned into rates over the last poll interval.
 * A value that the interval could not measure (no request finished, nothing was queried) is NaN
 * and plays no part in the decision.
 *
 * @param available     whether /metrics answered at all
 * @param kvUsage       KV cache in use, 0..1
 * @param waiting       requests the server has accepted but not scheduled
 * @param preemptions   requests evicted and recomputed during the interval
 * @param queueSeconds  mean time a request finished during the interval spent queued
 * @param prefixHitRate share of prompt tokens served from the prefix cache during the interval
 * @param prefillRate   uncached prompt tokens per second of prefill during the interval
 * @param rateLimited   whether the transport saw a 429 or 503 during the interval
 */
public record Sensors(
        boolean available,
        double kvUsage,
        double waiting,
        double preemptions,
        double queueSeconds,
        double prefixHitRate,
        double prefillRate,
        boolean rateLimited
) {
    public Sensors withPrefillRate(double rate) {
        return new Sensors(available, kvUsage, waiting, preemptions, queueSeconds, prefixHitRate, rate, rateLimited);
    }

    public static Sensors unavailable(boolean rateLimited) {
        return new Sensors(false, Double.NaN, Double.NaN, 0, Double.NaN, Double.NaN, Double.NaN,
                rateLimited);
    }
}
