package dev.pironi.status;

import dev.pironi.model.ChatMessage;
import dev.pironi.model.ModelResponse;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.ObjectMapper;

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
        reporter.modelResponse(new ModelResponse(
                "ok", 10, 20, 2_000_000_000L, 1_000_000_000L
        ));
        reporter.idle();
        reporter.close();

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("tool read_file"));
        assertTrue(output.contains("│ ready"));
        assertTrue(output.contains("│ 20.00 tok/s"));
        assertTrue(output.endsWith("\u001B[r\u001B[2J\u001B[H"));
    }

    @Test
    void printsPersistentOperationalActivityWithoutReasoning() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerminalStatusReporter reporter = new TerminalStatusReporter(
                "model", Path.of("/workspace/project"), 8_192, 8,
                new PrintStream(bytes, true, StandardCharsets.UTF_8)
        );
        var arguments = new ObjectMapper().createObjectNode()
                .put("path", "README.md")
                .put("content", "private material");

        reporter.skill("team-lead");
        reporter.toolStarted("apply_patch", arguments);
        reporter.toolFinished("apply_patch", true, 7);
        reporter.close();

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("• Using skill team-lead"));
        assertTrue(output.contains("• Editing README.md with apply_patch"));
        assertTrue(output.contains("✓ Completed apply_patch in 7 ms"));
        assertFalse(output.contains("private material"));
    }

    @Test
    void marksCloudEndToEndRateAsApproximate() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerminalStatusReporter reporter = new TerminalStatusReporter(
                "cloud-model",
                Path.of("/workspace/project"),
                8_192,
                8,
                new PrintStream(bytes, true, StandardCharsets.UTF_8)
        );

        reporter.modelResponse(new ModelResponse("ok", 10, 30, 2_000_000_000L));
        reporter.idle();
        reporter.close();

        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("│ ~15.00 tok/s"));
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

    @Test
    void fallsBackWithoutCallingStatusResizeWhenTerminalCapabilitiesAreMissing() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var terminal = new DumbTerminal(
                "windows-fallback-test",
                "windows-unsupported",
                new ByteArrayInputStream(new byte[0]),
                bytes,
                StandardCharsets.UTF_8
        )) {
            terminal.setSize(new Size(120, 30));
            TerminalStatusReporter reporter = new TerminalStatusReporter(
                    "model",
                    Path.of("C:/Users/test/Documents/project"),
                    8_192,
                    8,
                    new PrintStream(bytes, true, StandardCharsets.UTF_8),
                    terminal
            );

            assertTrue(!TerminalStatusReporter.supportsJLineStatus(terminal));
            reporter.idle();
            reporter.close();
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("ready"));
        }
    }

    @Test
    void statusIsClampedToTheTerminalWidth() throws Exception {
        // The raw redraw path (used when JLine reports no scroll-region capability, as on
        // Windows consoles) rewrites the row with a carriage return. A line wider than the
        // window wraps first, so the redraw erases only the wrapped remainder and every refresh
        // leaves another half-line behind - which is exactly what a narrow window shows.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var terminal = new org.jline.terminal.impl.ExternalTerminal(
                "test", "xterm", new ByteArrayInputStream(new byte[0]),
                bytes, StandardCharsets.UTF_8);
        terminal.setSize(new Size(40, 24));
        TerminalStatusReporter reporter = new TerminalStatusReporter(
                "model", Path.of("/workspace/project"), 8_192, 8,
                new PrintStream(bytes, true, StandardCharsets.UTF_8), terminal);

        String wide = "x".repeat(200);
        assertEquals(39, reporter.clampToWidth(wide).length(),
                "one column is left spare so the terminal does not wrap on the last cell");
        assertEquals("short", reporter.clampToWidth("short"), "short lines are untouched");
        reporter.close();
    }

    @Test
    void dumbTerminalGetsNoRepeatingStatusRow() throws Exception {
        // A dumb terminal cannot rewrite a row, so a periodic status would print one line per
        // refresh and bury the conversation.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var dumb = new DumbTerminal(new ByteArrayInputStream(new byte[0]), bytes);
        TerminalStatusReporter reporter = new TerminalStatusReporter(
                "model", Path.of("/workspace/project"), 8_192, 8,
                new PrintStream(bytes, true, StandardCharsets.UTF_8), dumb);
        reporter.tool("read_file");
        reporter.tool("write_file");
        reporter.idle();
        reporter.close();

        String rendered = bytes.toString(StandardCharsets.UTF_8);
        long statusRows = rendered.lines().filter(l -> l.contains("ctx ~")).count();
        assertEquals(0, statusRows, "no repeated status rows on a dumb terminal: " + rendered);
    }

    @Test
    void theStatusRowFollowsTheWorkspace() {
        var bytes = new java.io.ByteArrayOutputStream();
        TerminalStatusReporter reporter = new TerminalStatusReporter(
                "model", Path.of("/workspace/project"), 8_192, 8,
                new PrintStream(bytes, true, StandardCharsets.UTF_8)
        );

        reporter.idle();
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("project"),
                bytes.toString(StandardCharsets.UTF_8));

        bytes.reset();
        reporter.workspaceChanged(Path.of("/workspace/ccc"));

        // The row exists to say where work is happening; after a move it named the old place.
        String after = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(after.contains("ccc"), after);
        assertFalse(after.contains("project"), after);
    }

    @Test
    void keepsTheStatusRowOutOfTheAnswerWhileItIsPrinting() {
        // Run 2026-08-23T0219: the model wrote a PowerShell script and the row landed inside it -
        // "$seen[$m.ConversationTopic] = $trueprocessing | ~32.43 tok/s | sub 0/2". The script the
        // user was handed would not have run.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        TerminalStatusReporter reporter = new TerminalStatusReporter(
                "model", Path.of("/workspace/project"), 8_192, 8, out
        );

        reporter.outputStarted();
        out.print("$seen[$m.ConversationTopic] = $true");
        reporter.idle();                       // the ticker, arriving between two words
        reporter.tool("read_file");
        out.print("\n$results += ...");
        reporter.outputFinished();

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("$true\n$results"), "answer was interrupted: " + output);
        assertFalse(output.substring(0, output.indexOf("$results")).contains("ready"),
                "a status row was drawn into the answer: " + output);
    }

    @Test
    void drawsTheRowItHeldOnceThePrintingIsOver() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerminalStatusReporter reporter = new TerminalStatusReporter(
                "model", Path.of("/workspace/project"), 8_192, 8,
                new PrintStream(bytes, true, StandardCharsets.UTF_8)
        );

        reporter.outputStarted();
        reporter.tool("read_file");
        reporter.idle();
        reporter.outputFinished();

        // Holding must not mean losing: the row says what it would have said, not what it said
        // before the answer began.
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("ready"), output);
        assertFalse(output.contains("tool read_file"), "a superseded row was replayed: " + output);
    }

    @Test
    void anApprovalPromptInsideARunDoesNotFreeTheRowEarly() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        TerminalStatusReporter reporter = new TerminalStatusReporter(
                "model", Path.of("/workspace/project"), 8_192, 8, out
        );

        reporter.outputStarted();
        reporter.outputStarted();              // the approval prompt, nested
        reporter.outputFinished();
        out.print("still printing");
        reporter.idle();
        reporter.outputFinished();

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertFalse(output.substring(0, output.indexOf("still printing")).contains("ready"),
                "the inner prompt released the row: " + output);
    }

    @Test
    void holdsASubagentActivityLineRatherThanDroppingIt() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerminalStatusReporter reporter = new TerminalStatusReporter(
                "model", Path.of("/workspace/project"), 8_192, 8,
                new PrintStream(bytes, true, StandardCharsets.UTF_8)
        );

        reporter.outputStarted();
        reporter.skill("windows-outlook-teams");
        reporter.outputFinished();

        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("windows-outlook-teams"));
    }
}
