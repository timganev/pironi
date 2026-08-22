package dev.pironi.cli;

import dev.pironi.agent.AgentResult;
import dev.pironi.safety.ConsoleApprovalPolicy;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveShellTest {
    @Test
    void unknownCommandPointsAtTheRealSpelling() {
        assertEquals(" Did you mean /access allow-tool?",
                InteractiveShell.suggestionFor("/allow-tool"));
        assertEquals(" Directories move with /workspace PATH.",
                InteractiveShell.suggestionFor("/deny-dir"));
        assertEquals(" Directories move with /workspace PATH.",
                InteractiveShell.suggestionFor("/allow-dir"));
        assertEquals(" Did you mean /model?", InteractiveShell.suggestionFor("/modell"));
        assertEquals(" Type /help for the list.",
                InteractiveShell.suggestionFor("/completely-unrelated"));
    }

    @Test
    void approvalInteractionUsesShellInputAndBalancesStatusLifecycle() throws Exception {
        BufferedReader input = new BufferedReader(new StringReader("y\n"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InteractiveShell shell = new InteractiveShell(
                input,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                task -> new AgentResult(true, "unused", 1)
        );
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger finishes = new AtomicInteger();
        ConsoleApprovalPolicy.Interaction interaction = shell.approvalInteraction(
                starts::incrementAndGet, finishes::incrementAndGet
        );

        String answer = interaction.request("run_command", "safe preview");
        interaction.result("Approved.");

        assertEquals("y", answer);
        assertEquals(1, starts.get());
        assertEquals(1, finishes.get());
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Allow tool 'run_command'?"));
        assertTrue(output.contains("safe preview"));
        assertTrue(output.contains("Approved."));
    }

    @Test
    void doesNotPrintStreamedAnswerTwice() throws Exception {
        BufferedReader input = new BufferedReader(new StringReader("hello\n/exit\n"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InteractiveShell shell = new InteractiveShell(
                input,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                task -> new AgentResult(true, "streamed answer", 1, true)
        );

        shell.run(null);

        assertTrue(!bytes.toString(StandardCharsets.UTF_8).contains("streamed answer"));
    }

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
    void handsOverTheRequestAloneWhenTheAgentKeepsTheConversation() throws Exception {
        BufferedReader input = new BufferedReader(new StringReader("first\nsecond\n/exit\n"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<String> tasks = new ArrayList<>();
        InteractiveShell shell = new InteractiveShell(
                input,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                new InteractiveShell.Runner() {
                    @Override public AgentResult run(String task) {
                        tasks.add(task);
                        return new AgentResult(true, "answer-" + tasks.size(), 1);
                    }

                    @Override public boolean carriesConversation() { return true; }
                }
        );

        assertEquals(0, shell.run(null));
        assertEquals(List.of("first", "second"), tasks);
        assertTrue(!bytes.toString(StandardCharsets.UTF_8).contains("Conversation memory:"));
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
                "/model qwen3-coder-next:q4_K_M\n/exit\n"
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
    }

    @Test
    void bareModelCommandSelectsProviderAndModel() throws Exception {
        BufferedReader input = new BufferedReader(new StringReader(
                "/model\n2\n1\n/exit\n"
        ));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicReference<String> selection = new AtomicReference<>();
        InteractiveShell shell = new InteractiveShell(
                input,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                task -> new AgentResult(true, "unused", 1),
                new InteractiveShell.ModelCommands() {
                    @Override public String currentProvider() { return "ollama"; }
                    @Override public String currentModel() { return "local"; }
                    @Override public void switchModel(String model) {}
                    @Override public void switchModel(String provider, String model) {
                        selection.set(provider + ":" + model);
                    }
                    @Override public List<InteractiveShell.ProviderChoice> availableProviders() {
                        return List.of(
                                new InteractiveShell.ProviderChoice("ollama", "Ollama"),
                                new InteractiveShell.ProviderChoice("deepseek", "DeepSeek")
                        );
                    }
                    @Override public List<String> availableModels(String provider) {
                        return provider.equals("deepseek")
                                ? List.of("deepseek-v4-flash") : List.of("local");
                    }
                }
        );

        shell.run(null);

        assertEquals("deepseek:deepseek-v4-flash", selection.get());
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains(
                "Model switched to deepseek-v4-flash on deepseek."
        ));
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

    @Test
    void providerFailureDoesNotCloseInteractiveShell() throws Exception {
        BufferedReader input = new BufferedReader(new StringReader("first\nsecond\n/exit\n"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicInteger calls = new AtomicInteger();
        InteractiveShell shell = new InteractiveShell(
                input,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                task -> {
                    if (calls.getAndIncrement() == 0) throw new java.io.IOException("HTTP 400");
                    return new AgentResult(true, "recovered", 1);
                }
        );

        assertEquals(0, shell.run(null));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(2, calls.get());
        assertTrue(output.contains("Request failed: HTTP 400"));
        assertTrue(output.contains("You can retry or change the model."));
        assertTrue(output.contains("recovered"));
        assertTrue(output.contains("Session closed."));
    }


    @Test
    void anAutoTurnStandsDownWhileATurnIsAlreadyRunning() throws Exception {
        List<String> tasks = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch userTurnEntered = new CountDownLatch(1);
        CountDownLatch releaseUserTurn = new CountDownLatch(1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InteractiveShell shell = new InteractiveShell(
                new BufferedReader(new StringReader("forecast, please\n/exit\n")),
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                task -> {
                    tasks.add(task);
                    if (tasks.size() == 1) {
                        userTurnEntered.countDown();
                        try {
                            releaseUserTurn.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return new AgentResult(true, "answer", 1, true);
                }
        );

        Thread repl = new Thread(() -> {
            try {
                shell.run(null);
            } catch (Exception ignored) {
                // the assertions below speak for the run
            }
        }, "repl");
        repl.start();
        assertTrue(userTurnEntered.await(5, TimeUnit.SECONDS), "the user turn never started");

        // The sub-agent finished while that turn is still in flight. A second loop beside it took
        // the finished results away from the first, which then stalled and redid the child's work.
        shell.autoTurnCallback().run();
        assertFalse(
                awaitCount(tasks, 2),
                "a second agent loop ran beside the one already in flight: " + tasks
        );

        releaseUserTurn.countDown();
        repl.join(10_000);
        assertEquals(1, tasks.size(), "the turn in flight drains finished sub-agents by itself");
    }

    /** @return true once the text shows up, false if it has not within a fair window */
    private static boolean awaitOutput(ByteArrayOutputStream bytes, String text) throws Exception {
        return awaitTrue(() -> bytes.toString(StandardCharsets.UTF_8).contains(text));
    }

    /** @return true once the list reaches {@code size}, false if it has not within a fair window */
    private static boolean awaitCount(List<String> items, int size) throws Exception {
        return awaitTrue(() -> items.size() >= size);
    }

    private static boolean awaitTrue(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
