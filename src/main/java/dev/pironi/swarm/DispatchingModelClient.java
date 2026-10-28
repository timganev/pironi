package dev.pironi.swarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.model.ChatMessage;
import dev.pironi.model.ModelClient;
import dev.pironi.model.ModelResponse;
import dev.pironi.model.RateLimitSignals;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
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
 * share one view of how loaded the server is. Every answer teaches the controller's limiter how
 * many requests the server takes; a background poller adds vLLM's /metrics where there is one
 * (not with --swarm-blind) and 429/503 answers everywhere.
 *
 * <p>The cost of a request is its uncached prompt: what this conversation sent last time is in
 * the server's prefix cache, so only the part after it is new. A conversation is recognised by its
 * first two messages - the system prompt and the task - which no later turn changes.
 */
public final class DispatchingModelClient implements ModelClient, AutoCloseable {
    /** Rough characters per token across code, prose and JSON; only the budget's scale depends on it. */
    static final double CHARS_PER_TOKEN = 3.5;
    private static final int REMEMBERED_CONVERSATIONS = 256;
    /** First requests of recent conversations, newest first, for spotting a shared start. */
    private static final int REMEMBERED_STARTS = 32;
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
    private final java.util.ArrayDeque<List<ChatMessage>> recentStarts = new java.util.ArrayDeque<>();
    private final Thread poller;
    private volatile boolean closed;
    private SwarmProfile profile;
    private String profileKey;

    public DispatchingModelClient(ModelClient delegate, DispatchController controller,
                                  VllmMetrics metrics, PrintWriter log) {
        this.delegate = delegate;
        this.controller = controller;
        this.metrics = metrics;
        this.log = log;
        // Polls even without /metrics: a 429 or 503 seen by the transport must still cut the limit.
        this.poller = Thread.ofVirtual().name("swarm-poller").start(this::poll);
    }

    /** The wrapper --swarm asks for, or the client itself when it is off. */
    public static ModelClient wrap(ModelClient client, SwarmSettings settings, URI baseUri, String apiKey,
                                   String model) {
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
        // Start high: at what the user or the server says, else at what was learned last time.
        SwarmProfile profile = new SwarmProfile(settings.profilePath());
        String key = SwarmProfile.key(baseUri, model);
        int slots = settings.maxParallel() > 0 ? 0 : serverSlots(baseUri, apiKey);
        int ceiling = settings.maxParallel() > 0 ? settings.maxParallel()
                : slots > 0 ? slots : ConcurrencyLimiter.SAFETY_CEILING;
        int learned = profile.limit(key);
        int start = settings.maxParallel() > 0 || slots > 0 ? ceiling
                : learned > 0 ? Math.min(learned, ceiling) : ConcurrencyLimiter.BLIND_START;
        DispatchingModelClient wrapped = new DispatchingModelClient(client,
                new DispatchController(ceiling, start, settings.budgetTokens()),
                settings.blind() ? null : new VllmMetrics(VllmMetrics.metricsUri(baseUri), apiKey), log);
        wrapped.profile = profile;
        wrapped.profileKey = key;
        wrapped.log("start limit=" + start + " ceiling=" + ceiling
                + (settings.maxParallel() > 0 ? " (--max-parallel)" : slots > 0 ? " (/props)" : "")
                + (learned > 0 ? " learned=" + learned : "") + (settings.blind() ? " blind" : ""));
        return wrapped;
    }

    /** How many requests the server says it runs at once (llama.cpp's /props total_slots), or 0. */
    static int serverSlots(URI baseUri, String apiKey) {
        String base = baseUri.toString().replaceAll("/+$", "");
        if (base.endsWith("/v1")) base = base.substring(0, base.length() - 3);
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/props"))
                    .timeout(Duration.ofSeconds(2)).GET();
            if (apiKey != null && !apiKey.isBlank()) b.header("Authorization", "Bearer " + apiKey);
            HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(b.build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() / 100 == 2 ? Math.max(0, slotsIn(r.body())) : 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    static int slotsIn(String propsJson) {
        try {
            return new ObjectMapper().readTree(propsJson).path("total_slots").asInt(0);
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public ModelResponse chat(List<ChatMessage> messages) throws IOException, InterruptedException {
        return dispatch(messages, () -> delegate.chat(messages));
    }

    @Override
    public ModelResponse chatText(List<ChatMessage> messages) throws IOException, InterruptedException {
        return dispatch(messages, () -> delegate.chatText(messages));
    }

    @Override
    public ModelResponse chatStreaming(List<ChatMessage> messages, Consumer<String> contentChunk)
            throws IOException, InterruptedException {
        return dispatch(messages, () -> delegate.chatStreaming(messages, contentChunk));
    }

    private interface Call {
        ModelResponse run() throws IOException, InterruptedException;
    }

    private ModelResponse dispatch(List<ChatMessage> messages, Call call) throws IOException, InterruptedException {
        DispatchController.Permit permit = admit(uncachedTokens(messages));
        try {
            ModelResponse response = call.run();
            controller.release(permit, generatedTokens(response));
            return response;
        } catch (IOException e) {
            controller.releaseFailed(permit, overloaded(e));
            throw e;
        } finally {
            controller.release(permit);  // no-op unless something else was thrown
        }
    }

    /** What the server generated; estimated from the text where it does not report usage. */
    static long generatedTokens(ModelResponse response) {
        if (response == null) return 0;
        if (response.outputTokens() > 0) return response.outputTokens();
        String content = response.content();
        return content == null ? 0 : (long) Math.ceil(content.length() / CHARS_PER_TOKEN);
    }

    /** A failure that says the server is full rather than that the request was wrong. */
    static boolean overloaded(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException) return true;
            String m = t.getMessage();
            if (m != null && (m.contains("HTTP 429") || m.contains("HTTP 503"))) return true;
        }
        return false;
    }

    /** What the swarm has learned the server takes, for the orchestrator. */
    public Parallelism parallelism() {
        return controller.parallelism();
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

    /**
     * Characters this request brings that the server has not seen, as tokens. A conversation's
     * own earlier request is in the prefix cache; a new conversation may still start with what
     * another one sent (the same system prompt and context, as forked agents do), so its first
     * request is compared with the recent first requests of others.
     */
    long uncachedTokens(List<ChatMessage> messages) {
        long total = 0;
        for (ChatMessage m : messages) total += m.content() == null ? 0 : m.content().length();
        long key = conversationKey(messages);
        long before;
        synchronized (sentChars) {
            Long previous = sentChars.put(key, total);
            if (previous != null && previous <= total) {
                before = previous;
            } else {
                before = 0;
                for (List<ChatMessage> other : recentStarts) before = Math.max(before, sharedChars(messages, other));
                recentStarts.addFirst(List.copyOf(messages));
                if (recentStarts.size() > REMEMBERED_STARTS) recentStarts.removeLast();
            }
        }
        return (long) Math.ceil((total - before) / CHARS_PER_TOKEN);
    }

    /** Content characters two requests share from the start: whole equal messages, then a common prefix. */
    static long sharedChars(List<ChatMessage> a, List<ChatMessage> b) {
        long shared = 0;
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            ChatMessage x = a.get(i);
            ChatMessage y = b.get(i);
            String cx = x.content() == null ? "" : x.content();
            String cy = y.content() == null ? "" : y.content();
            if (!x.role().equals(y.role())) break;
            if (cx.equals(cy)) {
                shared += cx.length();
                continue;
            }
            int n = Math.min(cx.length(), cy.length());
            int k = 0;
            while (k < n && cx.charAt(k) == cy.charAt(k)) k++;
            shared += k;
            break;
        }
        return shared;
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
        boolean lastSettled = false;
        int ticks = 0;
        while (!closed) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            long limits = RateLimitSignals.count();
            Sensors s = metrics == null ? Sensors.unavailable(limits > seenLimits) : metrics.read(limits > seenLimits);
            seenLimits = limits;
            DispatchController.Light light = controller.onTick(s);
            // Changes as they happen, and every ~15 s a heartbeat with the learned prefill rate.
            Parallelism p = controller.parallelism();
            if (light != last || p.limit() != lastWindow || p.settled() != lastSettled || ++ticks % 10 == 0) {
                log(String.format(java.util.Locale.ROOT,
                        "%s W=%d%s B=%s inflight=%d queued=%d kv=%.2f waiting=%.0f preempt=%.0f queue=%.2f hit=%.2f prefill=%.0f",
                        light, p.limit(), p.settled() ? " settled" : "",
                        controller.budgetActive() ? String.valueOf(controller.budget()) : "-", controller.inFlight(),
                        controller.queued(), s.kvUsage(), s.waiting(), s.preemptions(), s.queueSeconds(),
                        s.prefixHitRate(), controller.prefillRate()));
                last = light;
                lastWindow = p.limit();
                lastSettled = p.settled();
            }
            remember();
        }
    }

    /** Keeps the limit for the next run; only a settled one, so a search cut short is not kept. */
    private void remember() {
        int learned = controller.learnedLimit();
        if (profile != null && learned > 0) profile.remember(profileKey, learned);
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
        remember();
        if (poller != null) poller.interrupt();
        if (log != null) log.flush();
    }
}
