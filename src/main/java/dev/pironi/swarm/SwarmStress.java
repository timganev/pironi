package dev.pironi.swarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.pironi.model.ChatMessage;
import dev.pironi.model.ModelClient;
import dev.pironi.model.ModelResponse;
import dev.pironi.model.ProviderConfig;
import dev.pironi.model.ProviderType;
import dev.pironi.model.ReasoningGuard;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Load test for the dispatcher (т.12 phase 1): N agents in one process, each a conversation of a
 * few turns over its own slice of source code, all started at once - the moment a swarm chokes a
 * card. Run with and without the dispatcher and compare what the server saw.
 *
 * <pre>
 * java -cp pironi.jar dev.pironi.swarm.SwarmStress --base-url http://127.0.0.1:8000/v1 --model qwen36
 *      --key-env PIRONI_LOCAL_KEY --src DIR --agents 16 --turns 3 --scenario U --swarm on
 *      --thinking 2048 --out result.json [--log swarm.log]
 * </pre>
 * Scenario S: every agent starts from the same 12k-token context (a fork); U: each from its own.
 */
public final class SwarmStress {
    private SwarmStress() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) a.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        URI base = URI.create(a.get("base-url"));
        String model = a.get("model");
        String key = System.getenv(a.getOrDefault("key-env", "PIRONI_LOCAL_KEY"));
        int agents = Integer.parseInt(a.getOrDefault("agents", "16"));
        int turns = Integer.parseInt(a.getOrDefault("turns", "3"));
        boolean shared = a.getOrDefault("scenario", "U").equals("S");
        boolean swarm = a.getOrDefault("swarm", "off").equals("on");
        int thinking = Integer.parseInt(a.getOrDefault("thinking", "0"));
        String code = sourceText(Path.of(a.get("src")));
        String system = Files.readString(Path.of(a.get("system")));

        ModelClient plain = new ProviderConfig(ProviderType.OPENAI_COMPATIBLE, base, model, key,
                Duration.ofSeconds(1800), 131072, thinking + 600,
                new ReasoningGuard(thinking, true)).createClient();
        SwarmSettings settings = new SwarmSettings(swarm,
                Integer.parseInt(a.getOrDefault("max-parallel", "0")),
                Long.parseLong(a.getOrDefault("budget", String.valueOf(SwarmSettings.DEFAULT_BUDGET_TOKENS))),
                a.getOrDefault("blind", "off").equals("on"),
                a.containsKey("log") ? Path.of(a.get("log")) : null);
        ModelClient client = DispatchingModelClient.wrap(plain, settings, base, key);

        String salt = Long.toHexString(System.nanoTime());
        int slice = 12_000 * 4;  // ~12k tokens of Java
        List<Thread> threads = new ArrayList<>();
        List<ObjectNode> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        ObjectMapper om = new ObjectMapper();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < agents; i++) {
            int id = i;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    int off = shared ? 0 : id * slice;
                    List<ChatMessage> msgs = new ArrayList<>();
                    msgs.add(ChatMessage.system(system));
                    msgs.add(ChatMessage.user("[run " + salt + (shared ? "" : "-" + id) + "]\nContext:\n```java\n"
                            + cut(code, off, slice) + "\n```\n" + (shared ? "You are agent " + id + ". " : "")
                            + "Find the most likely bug in the code above and explain it in at most 150 words."));
                    for (int turn = 0; turn < turns; turn++) {
                        long s = System.nanoTime();
                        ModelResponse r = client.chatText(List.copyOf(msgs));
                        long e = System.nanoTime();
                        ObjectNode row = om.createObjectNode();
                        row.put("agent", id).put("turn", turn).put("start", (s - t0) / 1e9).put("end", (e - t0) / 1e9)
                                .put("latency", (e - s) / 1e9).put("prompt", r.promptTokens()).put("gen", r.outputTokens());
                        requests.add(row);
                        msgs.add(ChatMessage.assistant(r.content()));
                        msgs.add(ChatMessage.user("More context:\n```java\n"
                                + cut(code, off + slice + turn * 4_000 + id * 997, 4_000)
                                + "\n```\nSame question for this part, at most 150 words."));
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }));
        }
        for (Thread t : threads) t.join();
        double wall = (System.nanoTime() - t0) / 1e9;
        ObjectNode out = om.createObjectNode();
        out.put("agents", agents).put("turns", turns).put("scenario", shared ? "S" : "U").put("swarm", swarm)
                .put("thinking", thinking).put("wall", wall);
        long gen = 0;
        double lat = 0;
        double maxLat = 0;
        for (ObjectNode r : requests) {
            gen += r.get("gen").asLong();
            lat += r.get("latency").asDouble();
            maxLat = Math.max(maxLat, r.get("latency").asDouble());
        }
        out.put("requests", requests.size()).put("gen", gen).put("total_tps", gen / wall)
                .put("latency_avg", requests.isEmpty() ? 0 : lat / requests.size()).put("latency_max", maxLat);
        if (failure.get() != null) out.put("failure", String.valueOf(failure.get()));
        ArrayNode rows = out.putArray("rows");
        requests.forEach(rows::add);
        Files.writeString(Path.of(a.get("out")), om.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        if (client instanceof DispatchingModelClient d) d.close();
        System.out.println(om.writeValueAsString(out.without("rows")));
    }

    private static String cut(String s, int off, int len) {
        int start = Math.floorMod(off, Math.max(1, s.length() - len));
        return s.substring(start, Math.min(s.length(), start + len));
    }

    private static String sourceText(Path dir) throws IOException {
        StringBuilder b = new StringBuilder();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                b.append(Files.readString(p));
            }
        }
        return b.toString();
    }
}
