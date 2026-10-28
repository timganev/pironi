package dev.pironi.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts the 429 and 503 answers the transport has seen, so a dispatcher can treat them as the
 * server saying "too much" without the client knowing that a dispatcher exists.
 */
public final class RateLimitSignals {
    private static final AtomicLong COUNT = new AtomicLong();
    /** Whether a retry waits as long as Retry-After asks; on with --swarm, off otherwise. */
    private static volatile boolean honorRetryAfter;

    private RateLimitSignals() {
    }

    static void record(int statusCode) {
        if (statusCode == 429 || statusCode == 503) COUNT.incrementAndGet();
    }

    public static void honorRetryAfter(boolean on) {
        honorRetryAfter = on;
    }

    static boolean honorRetryAfter() {
        return honorRetryAfter;
    }

    public static long count() {
        return COUNT.get();
    }
}
