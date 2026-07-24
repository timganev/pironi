package dev.pironi.cli;

import dev.pironi.agent.AgentResult;
import org.jline.keymap.KeyMap;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

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

    private final List<String> conversationHistory = new ArrayList<>();

    public InteractiveShell(
            Terminal terminal,
            PrintStream output,
            Runner runner,
            ModelCommands modelCommands,
            ShellCommands shellCommands,
            Runnable promptRendered
    ) {
        this.terminal = terminal;
        this.lineReader = createLineReader(terminal);
        this.fallbackInput = null;
        this.output = output;
        this.runner = runner;
        this.modelCommands = modelCommands;
        this.shellCommands = shellCommands;
        this.promptRendered = promptRendered;
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
    }

    public interface ModelCommands {
        String currentProvider();
        String currentModel();
        void switchModel(String model) throws IOException;
        default String currentApproval() { return "ask"; }
        default void switchApproval(String approval) throws IOException {}
        default List<String> availableModels() { return List.of(currentModel()); }
    }

    public interface ShellCommands {
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
        String pruneSkills();
    }

    public int run(String initialTask) throws IOException, InterruptedException {
        if (initialTask != null && !initialTask.isBlank()) {
            AgentResult result = runner.run(initialTask);
            println(result.output());
            conversationHistory.add("User: " + initialTask);
            conversationHistory.add("Pironi: " + result.output());
        }

        while (true) {
            String line = readLine();
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

            // Build context from recent history (last 4 exchanges = 8 lines)
            StringBuilder context = new StringBuilder();
            int start = Math.max(0, conversationHistory.size() - 8);
            for (int i = start; i < conversationHistory.size(); i++) {
                context.append(conversationHistory.get(i)).append('\n');
            }
            int exchangeCount = Math.min(4, conversationHistory.size() / 2);
            println("Conversation memory: " + exchangeCount + "/4 exchanges");

            String fullTask = context.isEmpty() ? line : context + "Current request:\n" + line;
            AgentResult result = runner.run(fullTask);
            println(result.output());
            conversationHistory.add("User: " + line);
            conversationHistory.add("Pironi: " + result.output());

            // Keep only last 4 exchanges
            while (conversationHistory.size() > 8) {
                conversationHistory.removeFirst();
            }
        }
        return 0;
    }

    private void handleCommand(String line) throws IOException {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0];
        String arg = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/model" -> {
                if (arg.isEmpty()) {
                    println("Current model: " + modelCommands.currentModel());
                } else {
                    modelCommands.switchModel(arg);
                    println("Model switched to " + arg + ".");
                }
            }
            case "/provider" -> {
                println("Current provider: " + modelCommands.currentProvider());
            }
            case "/approval" -> {
                if (arg.isEmpty()) {
                    println("Current approval: " + modelCommands.currentApproval());
                } else {
                    modelCommands.switchApproval(arg);
                    println("Approval mode switched to " + arg + ".");
                }
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
            case "/sessions" -> {
                if (shellCommands != null) println(shellCommands.listSessions());
                else println("Sessions not available.");
            }
            case "/resume" -> {
                if (shellCommands != null) println(shellCommands.resumeSession(arg));
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
            case "/prune-skills" -> {
                if (shellCommands != null) println(shellCommands.pruneSkills());
                else println("Skills not available.");
            }
            default -> println("Unknown command: " + cmd);
        }
    }

    private void println(String text) {
        if (lineReader != null) {
            lineReader.printAbove(text);
        } else {
            output.println(text);
        }
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

    private record Command(String name, String description) {}

    private static List<Command> commands() {
        return List.of(
                new Command("/model", "Switch or show the current model"),
                new Command("/provider", "Show the current provider"),
                new Command("/approval", "Show or change approval mode (ask|auto|read-only)"),
                new Command("/clear", "Clear conversation memory"),
                new Command("/context", "Show current conversation context"),
                new Command("/sessions", "List saved sessions"),
                new Command("/resume", "Resume a saved session by ID"),
                new Command("/delete-session", "Delete a saved session"),
                new Command("/search", "Search saved sessions"),
                new Command("/compress", "Manage context compression (on|off|now|0.0-1.0)"),
                new Command("/skills", "List installed skills"),
                new Command("/skill", "Load a skill by name"),
                new Command("/save-skill", "Save last turn as a skill"),
                new Command("/forget-skill", "Archive a skill"),
                new Command("/prune-skills", "Remove stale skills"),
                new Command("/exit", "Close this session"),
                new Command("/quit", "Close this session")
        );
    }

    private static LineReader createLineReader(Terminal terminal) {
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("pironi")
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
                .variable(LineReader.LIST_MAX, 20)
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
