package dev.pironi.status;

import dev.pironi.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.Status;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalStatusReporterTest {
    @Test
    void formatsStableStatusLine() {
        assertEquals(
                "⠹ model │ project │ ctx ~7% │ working 18s │ turn 2/8",
                TerminalStatusReporter.formatLine("⠹", "model", "project", 7, 18, 2, 8)
        );
        assertEquals("<1%", TerminalStatusReporter.formatContextPercent(0));
    }

    @Test
    void estimatesAndCapsContextUsage() {
        assertEquals(
                25,
                TerminalStatusReporter.estimateContextPercent(
                        List.of(ChatMessage.user("x".repeat(36))),
                        40
                )
        );
        assertEquals(
                100,
                TerminalStatusReporter.estimateContextPercent(
                        List.of(ChatMessage.user("x".repeat(1_000))),
                        10
                )
        );
    }

    @Test
    void reservesBottomRowKeepsReadyStatusAndRestoresTerminal() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerminalStatusReporter reporter = new TerminalStatusReporter(
                "model",
                Path.of("/workspace/project"),
                8_192,
                8,
                new PrintStream(bytes, true, StandardCharsets.UTF_8)
        );

        reporter.tool("read_file");
        reporter.idle();
        reporter.close();

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("tool read_file"));
        assertTrue(output.contains("│ ready"));
        assertTrue(output.endsWith("\u001B[r\u001B[2J\u001B[H"));
    }

    @Test
    void usesJLineStatusAsTheSingleReservedTerminalRow() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var terminal = new DumbTerminal(
                "status-test",
                "xterm-256color",
                new ByteArrayInputStream(new byte[0]),
                bytes,
                StandardCharsets.UTF_8
        )) {
            terminal.setSize(new Size(100, 30));
            TerminalStatusReporter reporter = new TerminalStatusReporter(
                    "model",
                    Path.of("/workspace/project"),
                    8_192,
                    8,
                    new PrintStream(bytes, true, StandardCharsets.UTF_8),
                    terminal
            );

            reporter.idle();

            Status status = Status.getExistingStatus(terminal).orElseThrow();
            assertEquals(1, status.size());
            assertTrue(status.toString().contains("supported=true"));
            reporter.close();
        }
    }
}
