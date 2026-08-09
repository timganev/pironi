package dev.pironi.cli;

import dev.pironi.status.ThemeSettings;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Display;
import org.jline.utils.Status;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

final class ThemePicker {
    private static final List<Choice> COLORS = List.of(
            new Choice("Green", 2), new Choice("Cyan", 6),
            new Choice("Blue", 4), new Choice("Yellow", 3),
            new Choice("Magenta", 5), new Choice("Red", 1),
            new Choice("White", 7), new Choice("Gray", 8),
            new Choice("Orange", 208), new Choice("Default", -1)
    );
    private static final List<ElementChoice> ELEMENTS = List.of(
            new ElementChoice("User input", ThemeSettings.Element.USER),
            new ElementChoice("Agent answer", ThemeSettings.Element.AGENT),
            new ElementChoice("Tool / skill activity", ThemeSettings.Element.ACTIVITY),
            new ElementChoice("System messages", ThemeSettings.Element.SYSTEM),
            new ElementChoice("Errors", ThemeSettings.Element.ERROR)
    );

    private final Terminal terminal;
    private final BufferedReader fallbackInput;
    private final PrintStream output;

    ThemePicker(Terminal terminal, PrintStream output) {
        this.terminal = terminal; this.fallbackInput = null; this.output = output;
    }

    ThemePicker(BufferedReader input, PrintStream output) {
        this.terminal = null; this.fallbackInput = input; this.output = output;
    }

    boolean choose(ThemeSettings theme, ThemeStore store) throws IOException {
        if (terminal == null) return chooseFallback(theme, store);
        while (true) {
            List<String> labels = new ArrayList<>(ELEMENTS.stream().map(ElementChoice::label).toList());
            labels.add("Reset defaults"); labels.add("Cancel");
            int selected = select("🎨 Theme — Select Element", "Choose what to color", labels, null);
            if (selected < 0 || selected == ELEMENTS.size() + 1) return false;
            if (selected == ELEMENTS.size()) {
                theme.reset(); store.save(theme); return true;
            }
            ElementChoice element = ELEMENTS.get(selected);
            int previous = theme.color(element.element());
            int color = select("🎨 " + element.label() + " — Select Color",
                    "Move to preview; Enter saves, Esc cancels",
                    COLORS.stream().map(Choice::label).toList(), element.element());
            if (color < 0) {
                theme.color(element.element(), previous);
                continue;
            }
            theme.color(element.element(), COLORS.get(color).color());
            store.save(theme);
            return true;
        }
    }

    private int select(String title, String hint, List<String> choices,
            ThemeSettings.Element previewElement) throws IOException {
        Status status = Status.getExistingStatus(terminal).orElse(null);
        if (status != null) status.suspend();
        Display display = new Display(terminal, false);
        display.resize(terminal.getHeight(), terminal.getWidth());
        int selected = 0;
        Attributes original = terminal.enterRawMode();
        try {
            while (true) {
                synchronized (terminal) {
                    display.update(render(title, hint, choices, selected, previewElement), 0);
                    terminal.flush();
                }
                Action action = readAction();
                if (action == Action.CANCEL) return -1;
                if (action == Action.UP) selected = (selected - 1 + choices.size()) % choices.size();
                else if (action == Action.DOWN) selected = (selected + 1) % choices.size();
                else if (action == Action.ACCEPT) return selected;
            }
        } finally {
            synchronized (terminal) {
                display.update(List.of(), 0); display.reset();
                if (status != null) status.restore();
                terminal.flush();
            }
            terminal.setAttributes(original);
        }
    }

    private List<AttributedString> render(String title, String hint, List<String> choices,
            int selected, ThemeSettings.Element previewElement) {
        int width = 62;
        List<AttributedString> lines = new ArrayList<>();
        lines.add(new AttributedString("╭─ " + title + " "
                + "─".repeat(Math.max(1, width - title.length() - 5)) + "╮"));
        lines.add(new AttributedString(pad("│", width) + "│"));
        lines.add(new AttributedString(pad("│  " + hint, width) + "│"));
        lines.add(new AttributedString(pad("│", width) + "│"));
        for (int index = 0; index < choices.size(); index++) {
            String prefix = "│ " + (index == selected ? "❯ " : "  ") + choices.get(index);
            AttributedStringBuilder row = new AttributedStringBuilder().append(pad(prefix, 24));
            if (previewElement != null) {
                int color = COLORS.get(index).color();
                AttributedStyle style = color < 0 ? AttributedStyle.DEFAULT
                        : AttributedStyle.DEFAULT.foreground(color);
                row.style(style).append("Preview text").style(AttributedStyle.DEFAULT);
            }
            row.append(" ".repeat(Math.max(0, width - row.length()))).append("│");
            lines.add(row.toAttributedString());
        }
        lines.add(new AttributedString("╰" + "─".repeat(width - 1) + "╯"));
        return lines;
    }

    private boolean chooseFallback(ThemeSettings theme, ThemeStore store) throws IOException {
        output.println("Theme picker requires an interactive terminal. Current colors unchanged.");
        return false;
    }

    private Action readAction() throws IOException {
        int first = terminal.reader().read();
        if (first == '\r' || first == '\n') return Action.ACCEPT;
        if (first == 3 || first < 0) return Action.CANCEL;
        if (first != 27) return Action.IGNORE;
        int second = terminal.reader().read(100);
        if (second != '[' && second != 'O') return Action.CANCEL;
        return switch (terminal.reader().read(100)) {
            case 'A' -> Action.UP; case 'B' -> Action.DOWN; default -> Action.IGNORE;
        };
    }

    private static String pad(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }

    private record Choice(String label, int color) {}
    private record ElementChoice(String label, ThemeSettings.Element element) {}
    private enum Action { UP, DOWN, ACCEPT, CANCEL, IGNORE }
}
