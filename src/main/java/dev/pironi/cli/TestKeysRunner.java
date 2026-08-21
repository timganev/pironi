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
 * Deterministic keyboard-level smoke tests for the TUI, driving the same JLine terminal and
 * {@link InteractiveShell} as production. Input arrives as timed UTF-8 keystrokes rather than
 * whole lines, so completion, pauses, editing and Unicode decoding are exercised.
 *
 * <p>Usage: {@code java -jar pironi.jar --test-keys [scenario-file]}
 */
public final class TestKeysRunner {
    private static final int TERMINAL_COLUMNS = 100;
    // Tall enough for the whole slash menu plus the prompt. At 30 rows the 29-entry menu no
    // longer fitted, so JLine replaced it with "do you wish to see all 29 possibilities?" and
    // every scripted scenario stalled waiting for an answer it was never scripted to give.
    private static final int TERMINAL_ROWS = 44;

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
        List<Scenario> scenarios = parseScenarios(readResource(BUILT_IN_SCENARIOS));

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

    private static final String BUILT_IN_SCENARIOS = "/keys/built-in.txt";

    private static String readResource(String resource) throws IOException {
        try (var stream = TestKeysRunner.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Scenario resource missing: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Scenarios in the same text form the {@code --test-keys FILE} argument takes, so the ones
     * shipped here and the ones a user writes cannot drift apart. A {@code @scenario} line starts
     * one; {@code @require}, {@code @forbid} and {@code @task} state what its output must and must
     * not contain. Everything else is a step, and a file with no {@code @} lines is still a single
     * unnamed scenario, which is what the argument used to accept.
     */
    private static List<Scenario> parseScenarios(String content) {
        List<Scenario> scenarios = new ArrayList<>();
        String name = "";
        List<TestStep> steps = new ArrayList<>();
        List<String> required = new ArrayList<>();
        List<String> forbidden = new ArrayList<>();
        List<String> tasks = new ArrayList<>();
        for (String rawLine : content.split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("@scenario")) {
                if (!steps.isEmpty()) {
                    scenarios.add(new Scenario(name, List.copyOf(steps), List.copyOf(required),
                            List.copyOf(forbidden), List.copyOf(tasks)));
                }
                name = line.substring("@scenario".length()).strip();
                steps = new ArrayList<>();
                required = new ArrayList<>();
                forbidden = new ArrayList<>();
                tasks = new ArrayList<>();
            } else if (line.startsWith("@require")) {
                required.add(text(line, "@require"));
            } else if (line.startsWith("@forbid")) {
                forbidden.add(text(line, "@forbid"));
            } else if (line.startsWith("@task")) {
                tasks.add(text(line, "@task"));
            } else {
                steps.add(parseStep(rawLine));
            }
        }
        if (!steps.isEmpty()) {
            scenarios.add(new Scenario(name, List.copyOf(steps), List.copyOf(required),
                    List.copyOf(forbidden), List.copyOf(tasks)));
        }
        return List.copyOf(scenarios);
    }

    /** Expected text is compared against output that may span lines, so \n is written escaped. */
    private static String text(String line, String directive) {
        return line.substring(directive.length()).strip().replace("\\n", "\n");
    }

    private static TestStep parseStep(String rawLine) {
        String line = rawLine.strip();
        int bracketEnd = line.indexOf(']');
        if (!line.startsWith("[") || bracketEnd < 0) {
            throw new IllegalArgumentException("Invalid scenario line: " + rawLine);
        }
        String rest = line.substring(bracketEnd + 1).strip();
        String[] expectedParts = rest.split("EXPECT:", 2);
        String action = expectedParts[0].strip();
        String expected = expectedParts.length > 1 ? expectedParts[1].strip() : null;
        if (action.startsWith("WAIT:")) {
            return new TestStep("", Long.parseLong(action.substring("WAIT:".length())), expected);
        }
        return new TestStep(action, 0, expected);
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
