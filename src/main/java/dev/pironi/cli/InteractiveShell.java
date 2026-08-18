package dev.pironi.cli;

import dev.pironi.agent.AgentResult;
import dev.pironi.safety.ConsoleApprovalPolicy;
import org.jline.keymap.KeyMap;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Highlighter;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import dev.pironi.status.ThemeSettings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class InteractiveShell {
    private static final String PROMPT = "› ";

    private final Terminal terminal;
    private final LineReader lineReader;
    private final BufferedReader fallbackInput;
    private final PrintStream output;
    private final Runner runner;
    private final ModelCommands modelCommands;
    private final ShellCommands shellCommands;
    private final Runnable promptRendered;
    private final ModelPicker modelPicker;
    private final ThemePicker themePicker;
    private final ThemeSettings theme;
    private final ThemeStore themeStore;

    private final List<String> conversationHistory = new ArrayList<>();
    private Runnable onShutdown = () -> { };
    private volatile boolean userWriting;
    private final Object autoTurnLock = new Object();
    /** Holds the last auto-turn answer that completed while the user was typing;
     * flushed to the screen when readLine() returns. */
    private volatile String pendingAutoTurnResult;

    public InteractiveShell(
            Terminal terminal,
            PrintStream output,
            Runner runner,
            ModelCommands modelCommands,
            ShellCommands shellCommands,
            Runnable promptRendered,
            ThemeSettings theme,
            ThemeStore themeStore
    ) {
        this.terminal = terminal;
        this.theme = theme;
        this.themeStore = themeStore;
        this.lineReader = createLineReader(terminal, theme);
        this.fallbackInput = null;
        this.output = output;
        this.runner = runner;
        this.modelCommands = modelCommands;
        this.shellCommands = shellCommands;
        this.promptRendered = promptRendered;
        this.modelPicker = new ModelPicker(terminal, lineReader, output);
        this.themePicker = new ThemePicker(terminal, output);
    }

    public InteractiveShell(
            Terminal terminal, PrintStream output, Runner runner, ModelCommands modelCommands,
            ShellCommands shellCommands, Runnable promptRendered
    ) {
        this(terminal, output, runner, modelCommands, shellCommands, promptRendered,
                new ThemeSettings(), new ThemeStore(Path.of(System.getProperty("user.home"), ".pironi")));
    }

    InteractiveShell(
            BufferedReader input,
            PrintStream output,
            Runner runner,
            ModelCommands modelCommands,
            Runnable promptRendered
    ) {
        this.terminal = null;
        this.lineReader = null;
        this.fallbackInput = input;
        this.output = output;
        this.runner = runner;
        this.modelCommands = modelCommands;
        this.shellCommands = null;
        this.promptRendered = promptRendered;
        this.modelPicker = new ModelPicker(input, output);
        this.theme = new ThemeSettings();
        this.themeStore = new ThemeStore(Path.of(System.getProperty("user.home"), ".pironi"));
        this.themePicker = new ThemePicker(input, output);
    }

    InteractiveShell(
            BufferedReader input,
            PrintStream output,
            Runner runner,
            ModelCommands modelCommands
    ) {
        this(input, output, runner, modelCommands, () -> {});
    }

    InteractiveShell(
            BufferedReader input,
            PrintStream output,
            Runner runner
    ) {
        this(input, output, runner, new ModelCommands() {
            @Override public String currentProvider() { return "ollama"; }
            @Override public String currentModel() { return "model"; }
            @Override public void switchModel(String m) {}
            @Override public String currentApproval() { return "ask"; }
            @Override public void switchApproval(String a) {}
            @Override public List<String> availableModels() { return List.of("model"); }
        }, () -> {});
    }

    @FunctionalInterface
    public interface Runner {
        AgentResult run(String task) throws IOException, InterruptedException;

        /**
         * True when the agent keeps the conversation between tasks itself.
         *
         * <p>The shell then hands over the request alone. Prefixing the transcript it kept would
         * only repeat, in a lossy form, what the agent already has.</p>
         */
        default boolean carriesConversation() { return false; }
    }

    public interface ModelCommands {
        String currentProvider();
        String currentModel();
        void switchModel(String model) throws IOException;
        default void switchModel(String provider, String model) throws IOException {
            switchModel(model);
        }
        default String currentApproval() { return "ask"; }
        default void switchApproval(String approval) throws IOException {}
        default List<String> availableModels() { return List.of(currentModel()); }
        default List<ProviderChoice> availableProviders() {
            return List.of(new ProviderChoice(currentProvider(), currentProvider()));
        }
        default List<String> availableModels(String provider) throws IOException {
            return availableModels();
        }
    }

    public record ProviderChoice(String slug, String label) {
    }

    public interface ShellCommands {
        default String newSession() { return "New sessions not available."; }
        default String capabilities() { return "Capabilities not available."; }
        default String doctor() { return "Diagnostics not available."; }
        String listSessions();
        String resumeSession(String id);
        String deleteSession(String id);
        String searchSessions(String query);
        String compressStatus();
        String setCompression(String arg);
        String listSkills();
        String loadSkill(String name);
        String saveSkill(String title);
        default String pendingSkill() { return "No pending skill draft."; }
        default String acceptSkill(String mode) { return "No pending skill draft."; }
        default String rejectSkill() { return "No pending skill draft."; }
        String forgetSkill(String name);
        String pruneSkills();
        /** Shows or changes session access: "", "allow-dir PATH", "deny-tool NAME", ... */
        default String access(String argument) { return "Access control not available."; }
        /** Stores or lists one-line preferences: "", a fact, or "forget N". */
        default String remember(String argument) { return "Memory not available."; }
    }

    public int run(String initialTask) throws IOException, InterruptedException {
        if (initialTask != null && !initialTask.isBlank()) {
            AgentResult result = runTask(initialTask);
            if (result != null) {
                if (!result.streamed()) printAgentAnswer(result.output());
                conversationHistory.add("User: " + initialTask);
                conversationHistory.add("Pironi: " + result.output());
            }
        }

        while (true) {
            userWriting = true;  // entering readLine — user is about to type
            String line = readLine();
            userWriting = false; // user pressed Enter, now processing the task

            // If an auto-turn completed while the user was typing (or idle), flush the result now.
            if (pendingAutoTurnResult != null) {
                String answer = pendingAutoTurnResult;
                pendingAutoTurnResult = null;
                // Only show if it hasn't already been streamed (auto-turn may stream long answers).
                println(""); // blank line before auto-turn result
                printAgentAnswer(answer);
                conversationHistory.add("Pironi: " + answer);
                while (conversationHistory.size() > 8) conversationHistory.removeFirst();
            }

            if (line == null) break;
            line = line.strip();
            if (line.isBlank()) continue;

            if (line.equals("/exit") || line.equals("/quit")) {
                println("Session closed.");
                break;
            }

            if (line.startsWith("/")) {
                handleCommand(line);
                continue;
            }

            String fullTask;
            if (runner.carriesConversation()) {
                fullTask = line;
            } else {
                // Build context from recent history (last 4 exchanges = 8 lines)
                StringBuilder context = new StringBuilder();
                int start = Math.max(0, conversationHistory.size() - 8);
                for (int i = start; i < conversationHistory.size(); i++) {
                    context.append(conversationHistory.get(i)).append('\n');
                }
                int exchangeCount = Math.min(4, conversationHistory.size() / 2);
                println("Conversation memory: " + exchangeCount + "/4 exchanges");
                fullTask = context.isEmpty() ? line : context + "Current request:\n" + line;
            }
            AgentResult result = runTask(fullTask);
            if (result == null) continue;
            if (!result.streamed()) printAgentAnswer(result.output());
            conversationHistory.add("User: " + line);
            conversationHistory.add("Pironi: " + result.output());

            // Keep only last 4 exchanges
            while (conversationHistory.size() > 8) {
                conversationHistory.removeFirst();
            }
        }
        onShutdown.run();
        return 0;
    }

    /** Hook the CLI body wires up so running sub-agents are drained/stopped at exit. */
    public InteractiveShell onShutdown(Runnable shutdown) {
        if (shutdown != null) this.onShutdown = shutdown;
        return this;
    }

    /**
     * When a sub-agent finishes, this callback triggers a model turn automatically so the
     * user does not need to press Enter to see the collected result. The turn runs in a
     * virtual thread and its output is printed above the prompt (via printAbove).
     */
    public Runnable autoTurnCallback() {
        return () -> {
            synchronized (autoTurnLock) {
                if (userWriting) {
                    // User is typing — defer the result display until they press Enter.
                    // The auto-turn will still run (below) but the output is buffered.
                }
            }
            Thread.ofVirtual().name("pironi-auto-turn").start(() -> {
                try {
                    AgentResult result = runner.run(
                            "[системно известие] Провери дали има нови резултати от под-агенти и ги представи на потребителя.");
                    if (result == null) return;
                    String answer = result.output();
                    if (answer == null || answer.isBlank()) return;
                    conversationHistory.add("User: [auto-turn]");
                    conversationHistory.add("Pironi: " + answer);
                    while (conversationHistory.size() > 8) conversationHistory.removeFirst();
                    // Always buffer the answer — streaming from a virtual thread is unreliable
                    // with JLine, so we show it via the REPL loop or printAgentAnswer.
                    if (userWriting) {
                        pendingAutoTurnResult = answer;
                    } else {
                        printAgentAnswer(answer);
                    }
                } catch (Exception ignored) {
                    // auto-turn failures are silent; user retries on next message
                }
            });
        };
    }

    private AgentResult runTask(String task) throws InterruptedException {
        try {
            return runner.run(task);
        } catch (IOException | RuntimeException e) {
            String detail = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            printError("Request failed: " + detail);
            printError("You can retry or change the model.");
            return null;
        }
    }

    private void handleCommand(String line) throws IOException {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0];
        String arg = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/model" -> {
                try {
                    if (arg.isEmpty()) {
                        ModelPicker.Selection selection = modelPicker.choose(modelCommands);
                        if (selection != null) {
                            modelCommands.switchModel(selection.provider(), selection.model());
                            conversationHistory.clear();
                            println("Model switched to " + selection.model()
                                    + " on " + selection.provider() + ".");
                        }
                    } else {
                        modelCommands.switchModel(arg);
                        conversationHistory.clear();
                        println("Model switched to " + arg + ".");
                    }
                } catch (IllegalArgumentException | IOException e) {
                    println("Model selection failed: " + e.getMessage());
                }
            }
            case "/provider" -> {
                println("Current provider: " + modelCommands.currentProvider());
            }
            case "/theme" -> {
                try {
                    if (themePicker.choose(theme, themeStore)) {
                        println("Theme saved.");
                    }
                } catch (IOException e) {
                    printError("Theme selection failed: " + e.getMessage());
                }
            }
            case "/approval" -> {
                if (arg.isEmpty()) {
                    println("Current approval: " + modelCommands.currentApproval());
                } else {
                    modelCommands.switchApproval(arg);
                    println("Approval mode switched to " + arg + ".");
                }
            }
            case "/help" -> {
                StringBuilder help = new StringBuilder("Commands:");
                for (Command command : commands()) {
                    help.append(System.lineSeparator()).append("  ")
                            .append(command.name()).append(" — ").append(command.description());
                }
                println(help.toString());
            }
            case "/clear" -> {
                conversationHistory.clear();
                println("Conversation memory: 0/4 exchanges");
            }
            case "/context" -> {
                if (conversationHistory.isEmpty()) {
                    println("Conversation memory: 0/4 exchanges");
                } else {
                    int exchanges = Math.min(4, conversationHistory.size() / 2);
                    println("Conversation memory: " + exchanges + "/4 exchanges");
                    for (String h : conversationHistory) {
                        println("  " + h);
                    }
                }
            }
            case "/new" -> {
                if (shellCommands != null) {
                    String result = shellCommands.newSession();
                    if (result.startsWith("New session started:")) {
                        conversationHistory.clear();
                    }
                    println(result);
                } else println("Sessions not available.");
            }
            case "/capabilities" -> {
                if (shellCommands != null) println(shellCommands.capabilities());
                else println("Capabilities not available.");
            }
            case "/access" -> println(shellCommands == null
                    ? "Access control not available." : shellCommands.access(arg));
            case "/remember" -> println(shellCommands == null
                    ? "Memory not available." : shellCommands.remember(arg));
            case "/doctor" -> {
                if (shellCommands != null) println(shellCommands.doctor());
                else println("Diagnostics not available.");
            }
            case "/sessions" -> {
                if (shellCommands != null) println(shellCommands.listSessions());
                else println("Sessions not available.");
            }
            case "/resume" -> {
                if (shellCommands != null) {
                    String result = shellCommands.resumeSession(arg);
                    if (result.startsWith("Session scheduled for resume:")) {
                        conversationHistory.clear();
                    }
                    println(result);
                }
                else println("Sessions not available.");
            }
            case "/delete-session" -> {
                if (shellCommands != null) println(shellCommands.deleteSession(arg));
                else println("Sessions not available.");
            }
            case "/search" -> {
                if (shellCommands != null) println(shellCommands.searchSessions(arg));
                else println("Sessions not available.");
            }
            case "/compress" -> {
                if (shellCommands != null) {
                    if (arg.isEmpty()) println(shellCommands.compressStatus());
                    else println(shellCommands.setCompression(arg));
                } else println("Compression not available.");
            }
            case "/skills" -> {
                if (shellCommands != null) println(shellCommands.listSkills());
                else println("Skills not available.");
            }
            case "/skill" -> {
                if (shellCommands != null) println(shellCommands.loadSkill(arg));
                else println("Skills not available.");
            }
            case "/save-skill" -> {
                if (shellCommands != null) println(shellCommands.saveSkill(arg));
                else println("Skills not available.");
            }
            case "/pending-skill" -> {
                if (shellCommands != null) println(shellCommands.pendingSkill());
                else println("Skills not available.");
            }
            case "/accept-skill" -> {
                if (shellCommands != null) println(shellCommands.acceptSkill(arg));
                else println("Skills not available.");
            }
            case "/reject-skill" -> {
                if (shellCommands != null) println(shellCommands.rejectSkill());
                else println("Skills not available.");
            }
            case "/forget-skill" -> {
                if (shellCommands != null) println(shellCommands.forgetSkill(arg));
                else println("Skills not available.");
            }
            case "/prune-skills" -> {
                if (shellCommands != null) println(shellCommands.pruneSkills());
                else println("Skills not available.");
            }
            default -> printError("Unknown command: " + cmd);
        }
    }

    private void println(String text) {
        printAboveByLine(text, ThemeSettings.Element.SYSTEM);
    }

    /**
     * Prints one line at a time.
     *
     * <p>Handing a whole multi-line block to printAbove leaves the embedded newlines untouched,
     * and with the terminal in raw mode - which it is whenever the pinned status row is active -
     * a bare LF moves down without returning to column one. The result is text that steps further
     * right with every line, as /doctor and any multi-line answer showed on Windows.
     */
    private void printAboveByLine(String text, ThemeSettings.Element element) {
        if (lineReader == null) {
            output.println(text);
            return;
        }
        for (String line : text.split("\n", -1)) {
            lineReader.printAbove(new AttributedString(line, theme.style(element)));
        }
    }

    /**
     * Public entry used by the sub-agent events sink to render notifications (spawn/done)
     * above the prompt. Thread-safe: JLine synchronizes {@code printAbove} internally.
     */
    void printAbove(String text) {
        println(text);
    }

    private void printError(String text) {
        if (lineReader != null) {
            lineReader.printAbove(new AttributedString(text,
                    theme.style(ThemeSettings.Element.ERROR)));
        } else output.println(text);
    }

    private void printAgentAnswer(String text) {
        printAboveByLine(text, ThemeSettings.Element.AGENT);
    }

    private String readLine() throws IOException {
        if (fallbackInput != null) {
            output.print(PROMPT);
            output.flush();
            promptRendered.run();
            return fallbackInput.readLine();
        }

        promptRendered.run();
        try {
            return lineReader.readLine(PROMPT);
        } catch (UserInterruptException e) {
            return "";
        } catch (EndOfFileException e) {
            return null;
        }
    }

    ConsoleApprovalPolicy.Interaction approvalInteraction(
            Runnable outputStarted,
            Runnable outputFinished
    ) {
        return new ConsoleApprovalPolicy.Interaction() {
            @Override
            public String request(String toolName, String preview) throws IOException {
                outputStarted.run();
                try {
                    println("Allow tool '" + toolName + "'?");
                    println(preview);
                    if (lineReader != null) {
                        return lineReader.readLine("[y/N] ");
                    }
                    output.print("[y/N] ");
                    output.flush();
                    return fallbackInput.readLine();
                } catch (UserInterruptException | EndOfFileException ignored) {
                    return null;
                } finally {
                    outputFinished.run();
                    promptRendered.run();
                }
            }

            @Override
            public void result(String message) {
                println(message);
            }
        };
    }

    private record Command(String name, String description) {}

    private static List<Command> commands() {
        return List.of(
                new Command("/model", "Switch or show the current model"),
                new Command("/theme", "Customize terminal colors"),
                new Command("/provider", "Show the current provider"),
                new Command("/approval", "Show or change approval mode (ask|auto|read-only)"),
                new Command("/help", "Show all slash commands"),
                new Command("/clear", "Clear conversation memory"),
                new Command("/context", "Show current conversation context"),
                new Command("/new", "Start a clean session"),
                new Command("/capabilities", "Show live runtime capabilities"),
                new Command("/access", "Show or change directory and tool access"),
                new Command("/remember", "Remember a preference; 'forget N' removes one"),
                new Command("/doctor", "Check Java, workspace, shell and network"),
                new Command("/sessions", "List saved sessions"),
                new Command("/resume", "Resume a saved session by ID"),
                new Command("/delete-session", "Delete a saved session"),
                new Command("/search", "Search saved sessions"),
                new Command("/compress", "Manage context compression (on|off|now|0.0-1.0)"),
                new Command("/skills", "List installed skills"),
                new Command("/skill", "Load a skill by name"),
                new Command("/save-skill", "Propose last turn as a skill draft"),
                new Command("/pending-skill", "Review the pending skill draft"),
                new Command("/accept-skill", "Persist draft; use 'replace' for reviewed updates"),
                new Command("/reject-skill", "Discard the pending skill draft"),
                new Command("/forget-skill", "Archive a skill"),
                new Command("/prune-skills", "Remove stale skills"),
                new Command("/exit", "Close this session"),
                new Command("/quit", "Close this session")
        );
    }

    private static LineReader createLineReader(Terminal terminal, ThemeSettings theme) {
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("pironi")
                .highlighter(new Highlighter() {
                    @Override
                    public AttributedString highlight(LineReader ignored, String buffer) {
                        return new AttributedString(
                                buffer,
                                theme.style(ThemeSettings.Element.USER)
                        );
                    }

                    @Override
                    public void setErrorPattern(Pattern errorPattern) {
                    }

                    @Override
                    public void setErrorIndex(int errorIndex) {
                    }
                })
                .completer((ignored, line, candidates) -> {
                    if (line.wordIndex() != 0 || !line.word().startsWith("/")) {
                        return;
                    }
                    for (Command command : commands()) {
                        candidates.add(new Candidate(
                                command.name(),
                                command.name(),
                                null,
                                command.description(),
                                null,
                                null,
                                true
                        ));
                    }
                })
                .option(LineReader.Option.AUTO_MENU, true)
                .option(LineReader.Option.AUTO_LIST, true)
                .option(LineReader.Option.LIST_ROWS_FIRST, true)
                .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .variable(LineReader.LIST_MAX, 100)
                .build();

        String slashMenuWidget = "pironi-slash-menu";
        reader.getWidgets().put(slashMenuWidget, () -> {
            reader.getBuffer().write('/');
            if (reader.getBuffer().length() == 1) {
                reader.callWidget(LineReader.LIST_CHOICES);
            }
            return true;
        });
        Reference slashBinding = new Reference(slashMenuWidget);
        for (String keyMapName : List.of(LineReader.MAIN, LineReader.EMACS, LineReader.VIINS)) {
            KeyMap<org.jline.reader.Binding> keyMap = reader.getKeyMaps().get(keyMapName);
            if (keyMap != null) {
                keyMap.bind(slashBinding, "/");
            }
        }
        return reader;
    }
}
