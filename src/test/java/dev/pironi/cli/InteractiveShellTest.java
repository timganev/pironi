package dev.pironi.cli;

import dev.pironi.agent.AgentResult;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveShellTest {
    @Test
    void completedExitCommandAllowsTrailingWhitespace() throws Exception {
        BufferedReader input = new BufferedReader(new StringReader("/exit \n"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InteractiveShell shell = new InteractiveShell(
                input,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                task -> new AgentResult(true, "unused", 1)
        );

        assertEquals(0, shell.run(null));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Session closed."));
        assertTrue(!output.contains("Unknown command:"));
    }

    @Test
    void staysOpenAndCarriesOnlyBoundedDialogueIntoNextTask() throws Exception {
        BufferedReader input = new BufferedReader(new StringReader(
                "first\nsecond\n/context\n/clear\n/context\n/exit\n"
        ));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<String> tasks = new ArrayList<>();
        InteractiveShell shell = new InteractiveShell(
                input,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                task -> {
                    tasks.add(task);
                    return new AgentResult(true, "answer-" + tasks.size(), 1);
                }
        );

        assertEquals(0, shell.run(null));
        assertEquals("first", tasks.get(0));
        assertTrue(tasks.get(1).contains("User: first"));
        assertTrue(tasks.get(1).contains("Pironi: answer-1"));
        assertTrue(tasks.get(1).endsWith("Current request:\nsecond"));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("› "));
        assertTrue(!output.contains("pironi> "));
        assertTrue(output.contains("Conversation memory: 2/4 exchanges"));
        assertTrue(output.contains("Conversation memory: 0/4 exchanges"));
        assertTrue(output.contains("Session closed."));
    }

    @Test
    void runsInitialTaskBeforePrompt() throws Exception {
        BufferedReader input = new BufferedReader(new StringReader("/exit\n"));
        List<String> tasks = new ArrayList<>();
        InteractiveShell shell = new InteractiveShell(
                input,
                new PrintStream(new ByteArrayOutputStream()),
                task -> {
                    tasks.add(task);
                    return new AgentResult(true, "done", 1);
                }
        );

        shell.run("initial");

        assertEquals(List.of("initial"), tasks);
    }

    @Test
    void slashShowsMenuAndModelCanBeChanged() throws Exception {
        BufferedReader input = new BufferedReader(new StringReader(
                "/model qwen3-coder-next:q4_K_M\n/model\n/exit\n"
        ));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicReference<String> model = new AtomicReference<>("qwen3.6:35b-a3b");
        InteractiveShell shell = new InteractiveShell(
                input,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                task -> new AgentResult(true, "unused", 1),
                new InteractiveShell.ModelCommands() {
                    @Override
                    public String currentProvider() {
                        return "ollama";
                    }

                    @Override
                    public String currentModel() {
                        return model.get();
                    }

                    @Override
                    public void switchModel(String newModel) {
                        model.set(newModel);
                    }
                }
        );

        assertEquals(0, shell.run(null));

        String output = bytes.toString(StandardCharsets.UTF_8);
        // /model qwen3-coder-next:q4_K_M should switch model
        assertTrue(output.contains("Model switched to qwen3-coder-next:q4_K_M."),
                "Should switch model");
        // /model (bare) should show current model
        assertTrue(output.contains("Current model: qwen3-coder-next:q4_K_M"),
                "Should show current model");
    }

    @Test
    void refreshesStatusAfterRenderingEveryPrompt() throws Exception {
        BufferedReader input = new BufferedReader(new StringReader("\n/exit\n"));
        java.util.concurrent.atomic.AtomicInteger refreshes =
                new java.util.concurrent.atomic.AtomicInteger();
        InteractiveShell shell = new InteractiveShell(
                input,
                new PrintStream(new ByteArrayOutputStream()),
                task -> new AgentResult(true, "unused", 1),
                new InteractiveShell.ModelCommands() {
                    @Override
                    public String currentProvider() {
                        return "ollama";
                    }

                    @Override
                    public String currentModel() {
                        return "model";
                    }

                    @Override
                    public void switchModel(String model) {
                    }
                },
                refreshes::incrementAndGet
        );

        shell.run(null);

        assertTrue(refreshes.get() >= 2, "Expected at least 2 refreshes, got " + refreshes.get());
    }

    @Test
    void approvalCommandShowsAndSwitchesCurrentMode() throws Exception {
        BufferedReader input = new BufferedReader(new StringReader(
                "/approval\n/approval auto\n/approval\n/exit\n"
        ));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicReference<String> approval = new AtomicReference<>("ask");
        InteractiveShell shell = new InteractiveShell(
                input,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                task -> new AgentResult(true, "unused", 1),
                new InteractiveShell.ModelCommands() {
                    @Override
                    public String currentProvider() {
                        return "openrouter";
                    }

                    @Override
                    public String currentModel() {
                        return "model";
                    }

                    @Override
                    public void switchModel(String model) {
                    }

                    @Override
                    public String currentApproval() {
                        return approval.get();
                    }

                    @Override
                    public void switchApproval(String mode) {
                        approval.set(mode);
                    }
                }
        );

        shell.run(null);

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Current approval: ask"));
        assertTrue(output.contains("Approval mode switched to auto."));
        assertTrue(output.contains("Current approval: auto"));
    }
}
