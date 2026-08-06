package dev.pironi.cli;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.Attributes;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.jline.utils.Status;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

final class ModelPicker {
    private static final String BACK = "← Back";
    private static final String CANCEL = "Cancel";

    private final Terminal terminal;
    private final LineReader lineReader;
    private final BufferedReader fallbackInput;
    private final PrintStream output;

    ModelPicker(Terminal terminal, LineReader lineReader, PrintStream output) {
        this.terminal = terminal;
        this.lineReader = lineReader;
        this.fallbackInput = null;
        this.output = output;
    }

    ModelPicker(BufferedReader input, PrintStream output) {
        this.terminal = null;
        this.lineReader = null;
        this.fallbackInput = input;
        this.output = output;
    }

    Selection choose(InteractiveShell.ModelCommands commands) throws IOException {
        while (true) {
            List<InteractiveShell.ProviderChoice> providers = commands.availableProviders();
            List<String> providerLabels = providers.stream()
                    .map(provider -> provider.label()
                            + (provider.slug().equals(commands.currentProvider()) ? "  ← current" : ""))
                    .toList();
            int providerIndex = select(
                    "⚙ Model Picker — Select Provider",
                    "Current: " + commands.currentModel() + " on " + commands.currentProvider(),
                    append(providerLabels, CANCEL)
            );
            if (providerIndex < 0 || providerIndex >= providers.size()) {
                return null;
            }

            InteractiveShell.ProviderChoice provider = providers.get(providerIndex);
            List<String> models = commands.availableModels(provider.slug());
            int modelIndex = select(
                    "⚙ Model Picker — " + provider.label(),
                    "Select a model (" + models.size() + " available)",
                    append(models, BACK, CANCEL)
            );
            if (modelIndex < 0 || modelIndex == models.size() + 1) {
                return null;
            }
            if (modelIndex == models.size()) {
                continue;
            }
            return new Selection(provider.slug(), models.get(modelIndex));
        }
    }

    private int select(String title, String hint, List<String> choices) throws IOException {
        if (terminal == null || lineReader == null) {
            return selectFallback(title, hint, choices);
        }
        Status pickerStatus = Status.getExistingStatus(terminal)
                .orElseThrow(() -> new IOException("Terminal status is not initialized"));
        pickerStatus.suspend();
        Display display = new Display(terminal, false);
        display.resize(terminal.getHeight(), terminal.getWidth());
        int selected = 0;
        Attributes originalAttributes = terminal.enterRawMode();
        try {
            while (true) {
                synchronized (terminal) {
                    display.update(render(title, hint, choices, selected), 0);
                    terminal.flush();
                }
                Action action = readAction();
                if (action == Action.CANCEL) {
                    return -1;
                }
                if (action == Action.UP) {
                    selected = (selected - 1 + choices.size()) % choices.size();
                } else if (action == Action.DOWN) {
                    selected = (selected + 1) % choices.size();
                } else if (action == Action.ACCEPT) {
                    return selected;
                }
            }
        } finally {
            synchronized (terminal) {
                display.update(List.of(), 0);
                display.reset();
                pickerStatus.restore();
                terminal.flush();
            }
            terminal.setAttributes(originalAttributes);
        }
    }

    private int selectFallback(String title, String hint, List<String> choices) throws IOException {
        output.println(title);
        output.println(hint);
        for (int i = 0; i < choices.size(); i++) {
            output.println((i + 1) + ". " + choices.get(i));
        }
        output.print("Select › ");
        output.flush();
        String value = fallbackInput.readLine();
        if (value == null) return -1;
        try {
            int selected = Integer.parseInt(value.trim()) - 1;
            return selected >= 0 && selected < choices.size() ? selected : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private Action readAction() throws IOException {
        int first = terminal.reader().read();
        if (first == '\r' || first == '\n') return Action.ACCEPT;
        if (first == 3 || first < 0) return Action.CANCEL;
        if (first != 27) return Action.IGNORE;

        int second = terminal.reader().read(100);
        if (second != '[' && second != 'O') return Action.CANCEL;
        int third = terminal.reader().read(100);
        return switch (third) {
            case 'A' -> Action.UP;
            case 'B' -> Action.DOWN;
            default -> Action.IGNORE;
        };
    }

    private static List<AttributedString> render(
            String title, String hint, List<String> choices, int selected
    ) {
        int width = Math.max(46, Math.min(84, Math.max(
                title.length() + 4,
                Math.max(hint.length() + 4, choices.stream().mapToInt(String::length).max().orElse(0) + 8)
        )));
        List<AttributedString> lines = new ArrayList<>();
        lines.add(new AttributedString("╭─ " + title + " " + "─".repeat(width - title.length() - 5) + "╮"));
        lines.add(new AttributedString(pad("│", width) + "│"));
        lines.add(new AttributedString(pad("│  " + hint, width) + "│"));
        lines.add(new AttributedString(pad("│", width) + "│"));
        for (int i = 0; i < choices.size(); i++) {
            lines.add(new AttributedString(pad("│ " + (i == selected ? "❯ " : "  ") + choices.get(i), width) + "│"));
        }
        lines.add(new AttributedString("╰" + "─".repeat(width - 1) + "╯"));
        return lines;
    }

    private static String pad(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }

    private static List<String> append(List<String> values, String... suffix) {
        List<String> result = new ArrayList<>(values);
        result.addAll(List.of(suffix));
        return List.copyOf(result);
    }

    record Selection(String provider, String model) {
    }

    private enum Action {
        UP, DOWN, ACCEPT, CANCEL, IGNORE
    }
}
