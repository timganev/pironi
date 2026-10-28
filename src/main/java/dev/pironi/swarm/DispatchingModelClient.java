package dev.pironi.swarm;

import dev.pironi.model.ChatMessage;
import dev.pironi.model.ModelClient;
import dev.pironi.model.ModelResponse;
import dev.pironi.model.RateLimitSignals;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Sends every model call through a {@link DispatchController}, so the parent and its children
 * share one view of how loaded the card is. A background poller reads /metrics and feeds the
 * controller; without /metrics only 429/503 answers move it.
 *
 * <p>The cost of a request is its uncached prompt: what this conversation sent last time is in
 * the server's prefix cache, so only the part after it is new. A conversation is recognised by its
 * first two messages - the system prompt and the task - which no later turn changes.
 */
public final class DispatchingModelClient implements ModelClient, AutoCloseable {
    /** Rough characters per token across code, prose and JSON; only the budget's scale depends on it. */
    static final double CHARS_PER_TOKEN = 3.5;
    private static final int REMEMBERED_CONVERSATIONS = 256;
    private static final long POLL_MILLIS = 1_500;

    private final ModelClient delegate;
    private final DispatchController controller;
    private final VllmMetrics metrics;
    private final PrintWriter log;
    private final Map<Long, Long> sentChars = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
            return size() > REMEMBERED_CONVERSATIONS;
        }
    };
    private final Thread poller;
    private volatile boolean closed;

    public DispatchingModelClient(ModelClient delegate, DispatchController controller,
                                  VllmMetrics metrics, PrintWriter log) {
        this.delegate = delegate;
        this.controller = controller;
        this.metrics = metrics;
        this.log = log;
        this.poller = metrics == null ? null : Thread.ofVirtual().name("swarm-poller").start(this::poll);
    }

    /** The wrapper --swarm asks for, or the client itself when it is off. */
    public static ModelClient wrap(ModelClient client, SwarmSettings settings, URI baseUri, String apiKey) {
        if (settings == null || !settings.enabled()) return client;
        RateLimitSignals.honorRetryAfter(true);
        PrintWriter log = null;
        if (settings.logPath() != null) {
            try {
                log = new PrintWriter(Files.newBufferedWriter(settings.logPath(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND), true);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot open swarm log " + settings.logPath(), e);
            }
        }
        return new DispatchingModelClient(client,
                new DispatchController(2, settings.maxWindow(), settings.budgetTokens()),
                new VllmMetrics(VllmMetrics.metricsUri(baseUri), apiKey), log);
    }

    @Override
    public ModelResponse chat(List<ChatMessage> messages) throws IOException, InterruptedException {
        long tokens = uncachedTokens(messages);
        DispatchController.Permit permit = admit(tokens);
        try {
            return delegate.chat(messages);
        } finally {
            controller.release(permit);
        }
    }

    @Override
    public ModelResponse chatText(List<ChatMessage> messages) throws IOException, InterruptedException {
        long tokens = uncachedTokens(messages);
        DispatchController.Permit permit = admit(tokens);
        try {
            return delegate.chatText(messages);
        } finally {
            controller.release(permit);
        }
    }

    @Override
    public ModelResponse chatStreaming(List<ChatMessage> messages, Consumer<String> contentChunk)
            throws IOException, InterruptedException {
        long tokens = uncachedTokens(messages);
        DispatchController.Permit permit = admit(tokens);
        try {
            return delegate.chatStreaming(messages, contentChunk);
        } finally {
            controller.release(permit);
        }
    }

    private DispatchController.Permit admit(long tokens) throws InterruptedException {
        long t0 = System.nanoTime();
        DispatchController.Permit permit = controller.acquire(tokens);
        long waited = (System.nanoTime() - t0) / 1_000_000;
        if (waited >= 200) {
            log("wait " + waited + " ms for " + tokens + " uncached tokens");
        }
        return permit;
    }

    /** Characters this conversation adds since its last request, as tokens. */
    long uncachedTokens(List<ChatMessage> messages) {
        long total = 0;
        for (ChatMessage m : messages) total += m.content() == null ? 0 : m.content().length();
        long key = conversationKey(messages);
        long before;
        synchronized (sentChars) {
            Long previous = sentChars.put(key, total);
            before = previous == null || previous > total ? 0 : previous;
        }
        return (long) Math.ceil((total - before) / CHARS_PER_TOKEN);
    }

    private static long conversationKey(List<ChatMessage> messages) {
        long h = 1125899906842597L;
        for (int i = 0; i < Math.min(2, messages.size()); i++) {
            String c = messages.get(i).content();
            h = 31 * h + messages.get(i).role().hashCode();
            h = 31 * h + (c == null ? 0 : c.hashCode());
        }
        return h;
    }

    private void poll() {
        long seenLimits = RateLimitSignals.count();
        DispatchController.Light last = null;
        int lastWindow = -1;
        while (!closed) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            long limits = RateLimitSignals.count();
            Sensors s = metrics.read(limits > seenLimits);
            seenLimits = limits;
            DispatchController.Light light = controller.onTick(s);
            if (light != last || controller.window() != lastWindow) {
                log(String.format(java.util.Locale.ROOT,
                        "%s W=%d B=%d inflight=%d queued=%d kv=%.2f waiting=%.0f preempt=%.0f queue=%.2f hit=%.2f prefill=%.0f",
                        light, controller.window(), controller.budget(), controller.inFlight(),
                        controller.queued(), s.kvUsage(), s.waiting(), s.preemptions(), s.queueSeconds(),
                        s.prefixHitRate(), controller.prefillRate()));
                last = light;
                lastWindow = controller.window();
            }
        }
    }

    private void log(String line) {
        if (log != null) log.println(LocalTime.now().truncatedTo(ChronoUnit.MILLIS) + " " + line);
    }

    public DispatchController controller() {
        return controller;
    }

    @Override
    public void close() {
        closed = true;
        if (poller != null) poller.interrupt();
        if (log != null) log.flush();
    }
}
