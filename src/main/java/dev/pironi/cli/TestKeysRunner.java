package dev.pironi.cli;

import dev.pironi.agent.AgentResult;
import dev.pironi.status.TerminalStatusReporter;
import dev.pironi.status.ThemeSettings;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic keyboard-level smoke tests for the interactive TUI.
 *
 * <p>The harness drives the same JLine terminal and {@link InteractiveShell}
 * used in production. Input is sent as timed UTF-8 keystrokes, not as complete
 * command lines, so completion, pauses, editing and Unicode decoding are
 * exercised.</p>
 *
 * <p>Usage: {@code java -jar pironi.jar --test-keys [scenario-file]}</p>
 */
public final class TestKeysRunner {
    private static final int TERMINAL_COLUMNS = 100;
    private static final int TERMINAL_ROWS = 30;

    private TestKeysRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            runScriptFile(args[0]);
        } else {
            runBuiltInTests();
        }
    }

    public static void runBuiltInTests() throws Exception {
        List<Scenario> scenarios = List.of(
                new Scenario(
                        "slash menu survives a human pause",
                        List.of(
                                keys("/"),
                                waitFor(750),
                                keys("exit\\r")
                        ),
                        List.of("Session closed."),
                        List.of("Unknown command:"),
                        List.of()
                ),
                new Scenario(
                        "slash command can be filtered and completed",
                        List.of(
                                keys("/"),
                                keys("mo"),
                                keys("\\t"),
                                keys("\\r"),
                                keys("\u001B[B"),
                                keys("\u001B[A"),
                                keys("\u001B"),
                                waitFor(200),
                                keys("/exit\\r")
                        ),
                        List.of("Model Picker", "Session closed."),
                        List.of("Unknown command:"),
                        List.of()
                ),
                new Scenario(
                        "completed exit command tolerates JLine trailing space",
                        List.of(
                                keys("/"),
                                keys("ex"),
                                keys("\\t"),
                                keys("\\r")
                        ),
                        List.of("Session closed."),
                        List.of("Unknown command:"),
                        List.of()
                ),
                new Scenario(
                        "UTF-8 Bulgarian input reaches the agent unchanged",
                        List.of(
                                keys("Здравей, Пирони!\\r"),
                                keys("/exit\\r")
                        ),
                        List.of("Session closed."),
                        List.of("Unknown command:", "OK/exit"),
                        List.of("Здравей, Пирони!")
                ),
                new Scenario(
                        "backspace edits the submitted task",
                        List.of(
                                keys("abc\\bd\\r"),
                                keys("/exit\\r")
                        ),
                        List.of("Session closed."),
                        List.of("Unknown command:", "OK/exit"),
                        List.of("abd")
                ),
                new Scenario(
                        "completion cancellation preserves conversation history",
                        List.of(
                                keys("first\\r"),
                                keys("/"),
                                waitFor(300),
                                keys("\\e"),
                                keys("\\x15"),
                                keys("second\\r"),
                                keys("/exit\\r")
                        ),
                        List.of("Conversation memory: 1/4 exchanges", "Session closed."),
                        List.of("Unknown command:", "OK/exit", "OK/sessions"),
                        List.of(
                                "first",
                                "User: first\nPironi: OK\nCurrent request:\nsecond"
                        )
                ),
                new Scenario(
                        "resume clears unrelated shell conversation history",
                        List.of(
                                keys("old request\\r"),
                                keys("/resume saved\\r"),
                                keys("continued request\\r"),
                                keys("/exit\\r")
                        ),
                        List.of("Session scheduled for resume:", "Session closed."),
                        List.of("Unknown command:"),
                        List.of("old request", "continued request")
                ),
                new Scenario(
                        "new command starts with clean conversation history",
                        List.of(
                                keys("old request\\r"),
                                keys("/new\\r"),
                                keys("clean request\\r"),
                                keys("/exit\\r")
                        ),
                        List.of("New session started:", "Session closed."),
                        List.of("Unknown command:"),
                        List.of("old request", "clean request")
                ),
                new Scenario(
                        "capabilities command is available from slash menu",
                        List.of(keys("/capabilities\\r"), keys("/exit\\r")),
                        List.of("[capabilities]", "Session closed."),
                        List.of("Unknown command:"),
                        List.of()
                ),
                new Scenario(
                        "doctor command is available from slash menu",
                        List.of(keys("/doctor\\r"), keys("/exit\\r")),
                        List.of("[doctor]", "Session closed."),
                        List.of("Unknown command:"),
                        List.of()
                ),
                new Scenario(
                        "theme picker selects element previews color and saves",
                        List.of(
                                keys("/theme\r"),
                                waitFor(150),
                                keys("\u001B[B\r"),
                                waitFor(150),
                                keys("\u001B[B\u001B[B\u001B[B\u001B[B\r"),
                                waitFor(150),
                                keys("/exit\r")
                        ),
                        List.of("Theme", "Preview text", "Theme saved.", "Session closed."),
                        List.of("Theme selection failed", "Unknown command:"),
                        List.of()
                ),
                new Scenario(
                        "multiline Cyrillic answer uses JLine-safe rendering",
                        List.of(keys("wrap\\r"), keys("/exit\\r")),
                        List.of(
                                "Първи дълъг ред на кирилица",
                                "Втори ред остава след първия",
                                "Трети ред не се размества",
                                "Session closed."
                        ),
                        List.of("Unknown command:"),
                        List.of("wrap")
                )
        );

        PrintStream logger = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        int passed = 0;
        for (Scenario scenario : scenarios) {
            logger.println("=== " + scenario.name() + " ===");
            ScenarioResult result = runScenario(scenario.steps());
            List<String> failures = validate(scenario, result);
            if (failures.isEmpty()) {
                logger.println("PASS");
                passed++;
            } else {
                logger.println("FAIL");
                failures.forEach(failure -> logger.println("  - " + failure));
                logger.println("  Submitted tasks: " + result.tasks());
                logger.println("  Visible output: " + stripAnsi(result.terminalOutput()));
            }
        }
        logger.println("=== RESULTS: " + passed + "/" + scenarios.size() + " passed ===");
        if (passed != scenarios.size()) {
            throw new IllegalStateException("Keyboard smoke tests failed");
        }
    }

    /**
     * Scenario file format:
     *
     * <pre>
     * [type slash] /
     * [pause] WAIT:750
     * [finish] exit\r EXPECT:Session closed.
     * </pre>
     */
    public static void runScriptFile(String path) throws Exception {
        List<TestStep> steps = parseScript(Files.readString(Path.of(path)));
        ScenarioResult result = runScenario(steps);
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.println(stripAnsi(result.terminalOutput()));
        out.println("Submitted tasks: " + result.tasks());
        if (result.failure() != null) {
            throw new IllegalStateException("Shell failed", result.failure());
        }
        for (TestStep step : steps) {
            if (step.expectedResult() != null
                    && !result.terminalOutput().contains(step.expectedResult())) {
                throw new IllegalStateException(
                        "Expected terminal output was not found: " + step.expectedResult()
                );
            }
        }
    }

    private static ScenarioResult runScenario(List<TestStep> steps) throws Exception {
        PipedInputStream terminalInput = new PipedInputStream(16_384);
        PipedOutputStream keyboard = new PipedOutputStream(terminalInput);
        ByteArrayOutputStream terminalBytes = new ByteArrayOutputStream(32_768);
        List<String> submittedTasks = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Terminal terminal = new DumbTerminal(
                "pironi-keyboard-test",
                "xterm-256color",
                terminalInput,
                terminalBytes,
                StandardCharsets.UTF_8
        );
        terminal.setSize(new Size(TERMINAL_COLUMNS, TERMINAL_ROWS));

        PrintStream terminalOutput =
                new PrintStream(terminalBytes, true, StandardCharsets.UTF_8);
        ThemeSettings theme = new ThemeSettings();
        ThemeStore themeStore = new ThemeStore(Files.createTempDirectory("pironi-theme-test-"));
        TerminalStatusReporter status = new TerminalStatusReporter(
                "test-model",
                Path.of("/workspace/pironi"),
                8_192,
                8,
                terminalOutput,
                terminal,
                theme
        );
        InteractiveShell shell = new InteractiveShell(
                terminal,
                terminalOutput,
                task -> {
                    submittedTasks.add(task);
                    status.idle();
                    String answer = task.equals("wrap")
                            ? "Първи дълъг ред на кирилица ".repeat(5)
                                    + "\nВтори ред остава след първия"
                                    + "\nТрети ред не се размества"
                            : "OK";
                    return new AgentResult(true, answer, 1, false);
                },
                testModelCommands(),
                testShellCommands(),
                status::idle,
                theme,
                themeStore
        );

        Thread shellThread = Thread.ofVirtual().start(() -> {
            try {
                shell.run(null);
            } catch (Throwable e) {
                failure.set(e);
            }
        });

        try {
            for (TestStep step : steps) {
                if (step.waitMillis() > 0) {
                    Thread.sleep(step.waitMillis());
                } else {
                    keyboard.write(parseKeySequence(step.keys()));
                    keyboard.flush();
                    Thread.sleep(75);
                }
            }
            shellThread.join(3_000);
            if (shellThread.isAlive()) {
                failure.compareAndSet(null, new IllegalStateException(
                        "Shell did not terminate after the scripted keyboard input"
                ));
            }
        } finally {
            status.close();
            keyboard.close();
            terminalInput.close();
            terminal.close();
        }

        return new ScenarioResult(
                terminalBytes.toString(StandardCharsets.UTF_8),
                List.copyOf(submittedTasks),
                failure.get()
        );
    }

    private static List<String> validate(Scenario scenario, ScenarioResult result) {
        List<String> failures = new ArrayList<>();
        if (result.failure() != null) {
            failures.add("shell failure: " + result.failure());
        }
        if (!result.terminalOutput().contains("ready")) {
            failures.add("persistent status line was not rendered");
        }
        for (String expected : scenario.requiredOutput()) {
            if (!result.terminalOutput().contains(expected)) {
                failures.add("missing output: " + expected);
            }
        }
        for (String forbidden : scenario.forbiddenOutput()) {
            if (result.terminalOutput().contains(forbidden)) {
                failures.add("forbidden output: " + forbidden);
            }
        }
        if (!result.tasks().equals(scenario.expectedTasks())) {
            failures.add("expected tasks " + scenario.expectedTasks()
                    + " but got " + result.tasks());
        }
        return failures;
    }

    private static InteractiveShell.ModelCommands testModelCommands() {
        return new InteractiveShell.ModelCommands() {
            @Override
            public String currentProvider() {
                return "ollama";
            }

            @Override
            public String currentModel() {
                return "test-model";
            }

            @Override
            public void switchModel(String model) {
            }
        };
    }

    private static InteractiveShell.ShellCommands testShellCommands() {
        return new InteractiveShell.ShellCommands() {
            @Override public String newSession() { return "New session started: test-session"; }
            @Override public String capabilities() { return "[capabilities]"; }
            @Override public String doctor() { return "[doctor]"; }
            @Override public String listSessions() { return "[sessions]"; }
            @Override public String resumeSession(String id) {
                return "Session scheduled for resume: 3 messages";
            }
            @Override public String deleteSession(String id) { return "[delete " + id + "]"; }
            @Override public String searchSessions(String query) { return "[search " + query + "]"; }
            @Override public String compressStatus() { return "off"; }
            @Override public String setCompression(String argument) { return "[compress " + argument + "]"; }
            @Override public String listSkills() { return "[skills]"; }
            @Override public String loadSkill(String name) { return "[skill " + name + "]"; }
            @Override public String saveSkill(String title) { return "[save " + title + "]"; }
            @Override public String forgetSkill(String name) { return "[forget " + name + "]"; }
            @Override public String pruneSkills() { return "[prune]"; }
        };
    }

    private static byte[] parseKeySequence(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character != '\\' || i + 1 >= text.length()) {
                literal.append(character);
                continue;
            }

            flushLiteral(bytes, literal);
            char escaped = text.charAt(++i);
            switch (escaped) {
                case 'r' -> bytes.write('\r');
                case 'n' -> bytes.write('\n');
                case 't' -> bytes.write('\t');
                case 'b' -> bytes.write(127);
                case 'e' -> bytes.write(27);
                case 'u' -> bytes.write(new byte[]{27, '[', 'A'});
                case 'd' -> bytes.write(new byte[]{27, '[', 'B'});
                case 'x' -> {
                    if (i + 2 >= text.length()) {
                        throw new IllegalArgumentException("Incomplete hexadecimal key: " + text);
                    }
                    bytes.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                    i += 2;
                }
                default -> literal.append(escaped);
            }
        }
        flushLiteral(bytes, literal);
        return bytes.toByteArray();
    }

    private static void flushLiteral(ByteArrayOutputStream bytes, StringBuilder literal)
            throws IOException {
        if (!literal.isEmpty()) {
            bytes.write(literal.toString().getBytes(StandardCharsets.UTF_8));
            literal.setLength(0);
        }
    }

    private static String stripAnsi(String text) {
        return text
                .replaceAll("\u001B\\][^\u0007]*(?:\u0007|\u001B\\\\)", "")
                .replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", "")
                .replace("\r", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    private static TestStep keys(String keys) {
        return new TestStep(keys, 0, null);
    }

    private static TestStep waitFor(long millis) {
        return new TestStep("", millis, null);
    }

    private static List<TestStep> parseScript(String content) {
        List<TestStep> steps = new ArrayList<>();
        for (String rawLine : content.split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int bracketEnd = line.indexOf(']');
            if (!line.startsWith("[") || bracketEnd < 0) {
                throw new IllegalArgumentException("Invalid scenario line: " + rawLine);
            }
            String rest = line.substring(bracketEnd + 1).strip();
            String[] expectedParts = rest.split("EXPECT:", 2);
            String action = expectedParts[0].strip();
            String expected = expectedParts.length > 1 ? expectedParts[1].strip() : null;
            if (action.startsWith("WAIT:")) {
                steps.add(new TestStep(
                        "",
                        Long.parseLong(action.substring("WAIT:".length())),
                        expected
                ));
            } else {
                steps.add(new TestStep(action, 0, expected));
            }
        }
        return steps;
    }

    private record Scenario(
            String name,
            List<TestStep> steps,
            List<String> requiredOutput,
            List<String> forbiddenOutput,
            List<String> expectedTasks
    ) {
    }

    private record ScenarioResult(
            String terminalOutput,
            List<String> tasks,
            Throwable failure
    ) {
    }

    private record TestStep(
            String keys,
            long waitMillis,
            String expectedResult
    ) {
    }
}
