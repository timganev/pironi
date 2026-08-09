package dev.pironi.status;

import org.jline.utils.AttributedStyle;

import java.util.EnumMap;
import java.util.Map;

/** Mutable terminal colors shared by the shell, streamer, and activity reporter. */
public final class ThemeSettings {
    public enum Element { USER, AGENT, ACTIVITY, SYSTEM, ERROR }

    private static final Map<Element, Integer> DEFAULTS = Map.of(
            Element.USER, AttributedStyle.CYAN,
            Element.AGENT, AttributedStyle.GREEN,
            Element.ACTIVITY, AttributedStyle.WHITE,
            Element.SYSTEM, -1,
            Element.ERROR, AttributedStyle.RED
    );

    private final EnumMap<Element, Integer> colors = new EnumMap<>(Element.class);

    public ThemeSettings() { reset(); }

    public synchronized int color(Element element) { return colors.get(element); }

    public synchronized void color(Element element, int color) { colors.put(element, color); }

    public synchronized void reset() {
        colors.clear();
        colors.putAll(DEFAULTS);
    }

    public AttributedStyle style(Element element) {
        int color = color(element);
        return color < 0 ? AttributedStyle.DEFAULT : AttributedStyle.DEFAULT.foreground(color);
    }
}
