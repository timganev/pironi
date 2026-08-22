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
    /** One agent loop at a time: the loop and the memory behind it are not built to be shared. */
    private final java.util.concurrent.locks.ReentrantLock turnLock =
            new java.util.concurrent.locks.ReentrantLock();
    /** Holds the last auto-turn answer that completed while the user was typing;
     * flushed to the screen when readLine() returns. */
    private volatile String pendingAutoTurnResult;
    /** A pasted block stands as one line on the prompt and is restored when the line is sent. */
    private final PastedBlocks pastes = new PastedBlocks();
    /** Longer than any gap between two characters of one paste, shorter than any human pause. */
    private static final int PASTE_GAP_MILLIS = 3;
    /** A ceiling so a program piping at us cannot be joined into one unbounded line. */
    static final int MAX_PASTED_LINES = 500;

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
        if (this.lineReader != null) collapsePastedBlocks(this.lineReader, this.pastes);
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
         * True when the agent keeps the conversation itself, so the shell hands over the request
         * alone rather than repeating its own transcript in a lossy form.
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
        String forgetSkill(String name);
        String restoreSkill(String name);
        String pruneSkills();
        /** Shows or moves the directory writing tools may touch: "" or a path. */
        default String workspace(String argument) { return "Workspace switching not available."; }
        /** Shows what earlier runs established here, or "clear" to drop it. */
        default String findings(String argument) { return "Findings not available."; }
        /** Shows or changes tool access: "", "allow-tool NAME", "deny-tool NAME". */
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
            // The prompt showed a stand-in; the agent gets every character that was pasted.
            line = pastes.expand(ByteOrderMark.stripped(line)).strip();
            pastes.clear();
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
     * Triggers a model turn when a sub-agent finishes, so the result appears without the user
     * pressing Enter.
     */
    public Runnable autoTurnCallback() {
        return () -> Thread.ofVirtual().name("pironi-auto-turn").start(() -> {
            // A turn already in flight drains finished sub-agents itself at the top of its next
            // turn. Starting a second loop beside it took those results away from the first, which
            // then stalled on filler tool calls and finally redid the child's work for a second
            // answer. When the lock is taken there is nothing here left to do.
            if (!turnLock.tryLock()) return;
            try {
                AgentResult result = runner.run(
                        "[системно известие] Провери дали има нови резултати от под-агенти и ги представи на потребителя.");
                if (result == null) return;
                String answer = result.output();
                if (answer == null || answer.isBlank()) return;
                conversationHistory.add("User: [auto-turn]");
                conversationHistory.add("Pironi: " + answer);
                while (conversationHistory.size() > 8) conversationHistory.removeFirst();
                // The loop streams a validated answer as it lands. Printing it again here is what
                // put the same answer on the screen twice; only an unstreamed one needs a printer.
                if (result.streamed()) return;
                if (userWriting) {
                    pendingAutoTurnResult = answer;
                } else {
                    printAgentAnswer(answer);
                }
            } catch (Exception ignored) {
                // auto-turn failures are silent; user retries on next message
            } finally {
                turnLock.unlock();
            }
        });
    }

    private AgentResult runTask(String task) throws InterruptedException {
        turnLock.lockInterruptibly();
        try {
            return runner.run(task);
        } catch (IOException | RuntimeException e) {
            String detail = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            printError("Request failed: " + detail);
            printError("You can retry or change the model.");
            return null;
        } finally {
            turnLock.unlock();
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
            case "/findings" -> println(shellCommands == null
                    ? "Findings not available." : shellCommands.findings(arg));
            case "/workspace" -> println(shellCommands == null
                    ? "Workspace switching not available." : shellCommands.workspace(arg));
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
            case "/forget-skill" -> {
                if (shellCommands != null) println(shellCommands.forgetSkill(arg));
                else println("Skills not available.");
            }
            case "/restore-skill" -> {
                if (shellCommands != null) println(shellCommands.restoreSkill(arg));
                else println("Skills not available.");
            }
            case "/prune-skills" -> {
                if (shellCommands != null) println(shellCommands.pruneSkills());
                else println("Skills not available.");
            }
            default -> printError("Unknown command: " + cmd + suggestionFor(cmd));
        }
    }

    private void println(String text) {
        printAboveByLine(text, ThemeSettings.Element.SYSTEM);
    }

    /** Prints one line at a time. */
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
     * Public entry used by the sub-agent events sink to render notifications (spawn/done) above the
     * prompt.
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
            return withPastedRemainder(lineReader.readLine(PROMPT));
        } catch (UserInterruptException e) {
            return "";
        } catch (EndOfFileException e) {
            return null;
        }
    }

    /**
     * Joins the rest of a pasted block to the line that was just accepted.
     *
     * <p>A terminal that brackets a paste says so, and the widget on the prompt turns the whole
     * block into one line before anything is submitted. Windows does not: JLine's native terminal
     * delivers a paste as ordinary characters, so every newline in it is an Enter and eighteen
     * pasted lines became eighteen turns, each answered separately.
     *
     * <p>What is left to go on is timing. When a line is accepted because a person pressed Enter,
     * nothing follows it; when it was accepted because a paste contained a newline, the next line
     * is already in the buffer. Nobody types the second line of anything within a millisecond of
     * the first.
     */
    private String withPastedRemainder(String first) {
        if (terminal == null) return first;
        Block block = joinArrivedTogether(first, this::moreArrivedWithIt, () -> {
            try {
                return lineReader.readLine("");
            } catch (UserInterruptException | EndOfFileException stop) {
                return null;
            }
        }, MAX_PASTED_LINES);
        if (block.lines() > 1) {
            printAboveByLine(PastedBlocks.placeholder(block.lines()), ThemeSettings.Element.USER);
        }
        return block.text();
    }

    /** One submitted line, and how many lines of a paste were joined to make it. */
    record Block(String text, int lines) {
    }

    /**
     * Joins lines that arrived together into one, stopping at the first that did not.
     *
     * @param more  whether the next line is already waiting, which is what a paste looks like
     * @param next  reads that waiting line, or null when there is none to read
     * @param max   a ceiling, so a program piping at us cannot make one unbounded line
     */
    static Block joinArrivedTogether(String first, java.util.function.BooleanSupplier more,
            java.util.function.Supplier<String> next, int max) {
        if (first == null) return new Block(null, 0);
        if (max < 2 || !more.getAsBoolean()) return new Block(first, 1);
        StringBuilder block = new StringBuilder(first);
        int lines = 1;
        while (lines < max && more.getAsBoolean()) {
            String following = next.get();
            if (following == null) break;
            block.append(System.lineSeparator()).append(following);
            lines++;
        }
        return new Block(block.toString(), lines);
    }

    /** True when the terminal already holds the next character, which no typist manages. */
    private boolean moreArrivedWithIt() {
        try {
            return terminal.reader().peek(PASTE_GAP_MILLIS) >= 0;
        } catch (IOException unreadable) {
            return false;
        }
    }

    ConsoleApprovalPolicy.Interaction approvalInteraction(
            Runnable outputStarted,
            Runnable outputFinished
    ) {
        return new ConsoleApprovalPolicy.Interaction() {
            @Override
            public String request(String toolName, String preview) throws IOException {
                return request(toolName, preview, false);
            }

            @Override
            public String request(String toolName, String preview, boolean waivable)
                    throws IOException {
                outputStarted.run();
                String choices = waivable ? "[y/N/a=always] " : "[y/N] ";
                try {
                    println("Allow tool '" + toolName + "'?");
                    println(preview);
                    if (lineReader != null) {
                        return lineReader.readLine(choices);
                    }
                    output.print(choices);
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

    /**
     * Access lives under /access sub-verbs to keep the slash menu short, which makes /allow-tool
     * the mistake people actually type - and "Unknown command" points nowhere.
     */
    static String suggestionFor(String command) {
        String typed = command.startsWith("/") ? command.substring(1) : command;
        if (typed.isEmpty()) return " Type /help for the list.";
        for (String verb : ACCESS_VERBS) {
            if (verb.equals(typed)) return " Did you mean /access " + verb + "?";
        }
        // Muscle memory outlives a removed command, and these four were one intent all along.
        if (RETIRED_DIRECTORY_VERBS.contains(typed)) return " Directories move with /workspace PATH.";
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Command candidate : commands()) {
            String name = candidate.name().substring(1);
            int distance = editDistance(typed, name);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate.name();
            }
        }
        // A third of the name may differ; beyond that the "suggestion" is a guess that sends the
        // user somewhere unrelated, which is worse than saying nothing.
        int tolerance = Math.max(1, typed.length() / 3);
        return best != null && bestDistance <= tolerance
                ? " Did you mean " + best + "?"
                : " Type /help for the list.";
    }

    private static final List<String> ACCESS_VERBS = List.of("allow-tool", "deny-tool");

    private static final List<String> RETIRED_DIRECTORY_VERBS = List.of(
            "allow-dir", "deny-dir", "remember-dir", "forget-dir");

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitute = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
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
                new Command("/access", "Show or change tool access"),
                new Command("/workspace", "Show or take the directory the agent works in"),
                new Command("/findings", "Show what earlier runs established here; 'clear' drops it"),
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
                new Command("/forget-skill", "Archive a skill"),
                new Command("/restore-skill", "Restore an archived skill"),
                new Command("/prune-skills", "Remove stale skills"),
                new Command("/exit", "Close this session"),
                new Command("/quit", "Close this session")
        );
    }

    /**
     * Wraps JLine's own bracketed-paste widget so a pasted block leaves a one-line stand-in on the
     * prompt instead of the whole thing. The built-in does the reading; only what it left in the
     * buffer is rewritten, so the terminal handshake stays JLine's business.
     */
    private static void collapsePastedBlocks(LineReader reader, PastedBlocks pastes) {
        String widget = "pironi-paste";
        reader.getWidgets().put(widget, () -> {
            String before = reader.getBuffer().toString();
            int at = reader.getBuffer().cursor();
            reader.callWidget(LineReader.BEGIN_PASTE);
            String after = reader.getBuffer().toString();
            String pasted = insertedText(before, after, at);
            String shown = pastes.collapse(pasted);
            if (pasted.isEmpty() || shown.equals(pasted)) return true;
            reader.getBuffer().clear();
            reader.getBuffer().write(before.substring(0, at) + shown + before.substring(at));
            return true;
        });
        // xterm brackets a paste with these; JLine binds the first to BEGIN_PASTE by default.
        Reference binding = new Reference(widget);
        for (String keyMapName : List.of(LineReader.MAIN, LineReader.EMACS, LineReader.VIINS)) {
            KeyMap<org.jline.reader.Binding> keyMap = reader.getKeyMaps().get(keyMapName);
            if (keyMap != null) keyMap.bind(binding, "\033[200~");
        }
    }

    /** What the paste added, given where the cursor was. Empty when the buffer did not grow. */
    static String insertedText(String before, String after, int at) {
        if (after.length() <= before.length() || at > before.length()) return "";
        int added = after.length() - before.length();
        if (at + added > after.length()) return "";
        return after.substring(at, at + added);
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
