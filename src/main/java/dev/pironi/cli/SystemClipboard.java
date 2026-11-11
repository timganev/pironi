package dev.pironi.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Reads the desktop clipboard as text, for a Ctrl+V that reaches Pironi instead of the terminal.
 *
 * <p>A Linux terminal pastes on Ctrl+Shift+V and hands a bare Ctrl+V to the program inside it.
 * Dictation tools do not know that: they put the text on the clipboard and press Ctrl+V, which
 * JLine reads as quoted-insert. Nothing appeared, and the pending quoted-insert then swallowed
 * the ESC of the next bracketed paste, so the prompt showed {@code [200~text[201~}.
 */
final class SystemClipboard {
    private static final long TIMEOUT_SECONDS = 2;

    private SystemClipboard() {
    }

    /** @return the clipboard text, or an empty string when there is none or no tool can read it */
    static String read() {
        for (List<String> command : commands(System.getProperty("os.name", ""),
                System.getenv("WAYLAND_DISPLAY"))) {
            String text = run(command);
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    /** Windows is absent on purpose: its terminals paste on Ctrl+V themselves. */
    static List<List<String>> commands(String osName, String waylandDisplay) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return List.of(List.of("pbpaste"));
        if (!os.contains("linux")) return List.of();
        List<String> x11 = List.of("xclip", "-selection", "clipboard", "-o");
        if (waylandDisplay == null || waylandDisplay.isEmpty()) return List.of(x11);
        return List.of(List.of("wl-paste", "--no-newline", "--type", "text"), x11);
    }

    /** A paste is text on a line: the same line breaks a bracketed paste would leave. */
    static String normalized(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String run(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            Process started = process;
            // Read on another thread: a tool that never answers must not hold the prompt.
            var bytes = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return started.getInputStream().readAllBytes();
                } catch (IOException e) {
                    return new byte[0];
                }
            });
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return "";
            }
            return normalized(new String(
                    bytes.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            return "";
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }
}
