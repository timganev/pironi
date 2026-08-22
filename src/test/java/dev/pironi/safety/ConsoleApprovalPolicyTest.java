package dev.pironi.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.tool.Tool;
import dev.pironi.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleApprovalPolicyTest {
    private final JsonNode arguments = new ObjectMapper().createObjectNode();

    @Test
    void deniesMutationOnEofOrUnknownAnswer() {
        assertEquals(ApprovalDecision.DENY, policy("").decide(tool(true), arguments));
        assertEquals(ApprovalDecision.DENY, policy("maybe\n").decide(tool(true), arguments));
    }

    @Test
    void acceptsEnglishAndBulgarianApproval() {
        assertEquals(ApprovalDecision.ALLOW, policy("y\n").decide(tool(true), arguments));
        assertEquals(ApprovalDecision.ALLOW, policy("yes\n").decide(tool(true), arguments));
        assertEquals(ApprovalDecision.ALLOW, policy("д\n").decide(tool(true), arguments));
        assertEquals(ApprovalDecision.ALLOW, policy("да\n").decide(tool(true), arguments));
    }

    @Test
    void readOnlyDeniesMutationButAllowsReads() {
        ConsoleApprovalPolicy policy = new ConsoleApprovalPolicy(
                ApprovalMode.READ_ONLY,
                new BufferedReader(new StringReader("")),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertEquals(ApprovalDecision.DENY, policy.decide(tool(true), arguments));
        assertEquals(ApprovalDecision.ALLOW, policy.decide(tool(false), arguments));
    }

    @Test
    void modeCanBeChangedDuringTheSession() {
        ConsoleApprovalPolicy policy = new ConsoleApprovalPolicy(
                ApprovalMode.READ_ONLY,
                new BufferedReader(new StringReader("")),
                new PrintStream(new ByteArrayOutputStream())
        );

        policy.updateMode(ApprovalMode.AUTO);

        assertEquals(ApprovalMode.AUTO, policy.mode());
        assertEquals(ApprovalDecision.ALLOW, policy.decide(tool(true), arguments));
    }

    @Test
    void autoStillRequiresExplicitApprovalForPersistentContextFiles() {
        JsonNode protectedArguments = new ObjectMapper().createObjectNode()
                .put("path", ".pironi/SOUL.md")
                .put("content", "new identity");
        ConsoleApprovalPolicy denied = new ConsoleApprovalPolicy(
                ApprovalMode.AUTO,
                new BufferedReader(new StringReader("n\n")),
                new PrintStream(new ByteArrayOutputStream())
        );
        ConsoleApprovalPolicy allowed = new ConsoleApprovalPolicy(
                ApprovalMode.AUTO,
                new BufferedReader(new StringReader("yes\n")),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertEquals(ApprovalDecision.DENY, denied.decide(tool(true), protectedArguments));
        assertEquals(ApprovalDecision.ALLOW, allowed.decide(tool(true), protectedArguments));
        assertEquals(ApprovalDecision.DENY, denied.decide(
                tool(true),
                new ObjectMapper().createObjectNode()
                        .put("patch", "*** Update File: CLAUDE.md\n@@\n-old\n+new")
        ));
        assertEquals(ApprovalDecision.ALLOW, denied.decide(
                tool(true),
                new ObjectMapper().createObjectNode().put("path", "notes.md")
        ));
    }

    @Test
    void nonInteractiveAutoDeniesProtectedContextWithoutReadingInput() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleApprovalPolicy policy = new ConsoleApprovalPolicy(
                ApprovalMode.AUTO,
                new BufferedReader(new StringReader("yes\n")),
                new PrintStream(output),
                false
        );

        ApprovalDecision decision = policy.decide(
                tool(true),
                new ObjectMapper().createObjectNode().put("path", "SOUL.md")
        );

        assertEquals(ApprovalDecision.DENY, decision);
        assertTrue(output.toString().contains("require explicit approval"));
    }

    @Test
    void autoRunsAnActionThatWouldOtherwiseBeConfirmed() {
        // approval=auto used to prompt for every shell command anyway, on the reasoning that a
        // command's effect cannot be read off its name. A twenty-command run became twenty
        // prompts, and the documented meaning of the flag - "without asking" - was false.
        ConsoleApprovalPolicy interactive = new ConsoleApprovalPolicy(
                ApprovalMode.AUTO,
                new BufferedReader(new StringReader("n\n")),
                new PrintStream(new ByteArrayOutputStream()),
                true
        );

        assertEquals(ApprovalDecision.ALLOW, interactive.decide(new ExplicitTool(), arguments));
    }

    @Test
    void autoStillAsksBeforeTheBoundaryItselfMoves() {
        Tool boundary = new RuleChangingTool();
        ConsoleApprovalPolicy refusing = new ConsoleApprovalPolicy(
                ApprovalMode.AUTO,
                new BufferedReader(new StringReader("n\n")),
                new PrintStream(new ByteArrayOutputStream()),
                true
        );
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleApprovalPolicy batch = new ConsoleApprovalPolicy(
                ApprovalMode.AUTO,
                new BufferedReader(new StringReader("y\n")),
                new PrintStream(output),
                false
        );

        assertEquals(ApprovalDecision.DENY, refusing.decide(boundary, arguments));
        assertEquals(ApprovalDecision.DENY, batch.decide(boundary, arguments));
        assertTrue(output.toString().contains("requires explicit approval"));
    }

    @Test
    void alwaysStopsTheAskingForThatToolAndOnlyThatTool() {
        ConsoleApprovalPolicy policy = policy("a\n");
        Tool explicit = new ExplicitTool();

        assertEquals(ApprovalDecision.ALLOW, policy.decide(explicit, arguments));
        // The reader is exhausted: a second prompt would read EOF and deny, so an ALLOW here can
        // only mean it did not ask again.
        assertEquals(ApprovalDecision.ALLOW, policy.decide(explicit, arguments));
        assertEquals(ApprovalDecision.DENY, policy.decide(new OtherExplicitTool(), arguments));
    }

    @Test
    void alwaysCannotBeSaidAboutACredentialStore() {
        // The one promise that holds in every mode. "always" is taken as a yes for this call and
        // said to be un-waivable, rather than silently asking again next time.
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleApprovalPolicy policy = new ConsoleApprovalPolicy(
                ApprovalMode.AUTO,
                new BufferedReader(new StringReader("a\na\n")),
                new PrintStream(output),
                true
        );
        Tool guarded = new GuardedReadTool();

        assertEquals(ApprovalDecision.ALLOW, policy.decide(guarded, arguments));
        assertTrue(output.toString().contains("cannot be waived"), output.toString());
        assertEquals(ApprovalDecision.ALLOW, policy.decide(guarded, arguments));
        assertEquals(2, output.toString().split("cannot be waived", -1).length - 1,
                "it must ask every time: " + output);
    }


    private ConsoleApprovalPolicy policy(String input) {
        return new ConsoleApprovalPolicy(
                ApprovalMode.ASK,
                new BufferedReader(new StringReader(input)),
                new PrintStream(new ByteArrayOutputStream())
        );
    }

    private Tool tool(boolean mutating) {
        return new Tool() {
            public String name() { return "test"; }
            public String description() { return "test"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return mutating; }
            public ToolResult execute(JsonNode ignored) { return ToolResult.success(""); }
        };
    }

    private static final class ExplicitTool implements Tool {
        public String name() { return "explicit"; }
        public String description() { return "test"; }
        public String argumentSchema() { return "{}"; }
        public boolean mutating() { return true; }
        public boolean requiresExplicitApproval(JsonNode ignored) { return true; }
        public ToolResult execute(JsonNode ignored) { return ToolResult.success(""); }
    }

    private static final class OtherExplicitTool implements Tool {
        public String name() { return "other-explicit"; }
        public String description() { return "test"; }
        public String argumentSchema() { return "{}"; }
        public boolean mutating() { return true; }
        public boolean requiresExplicitApproval(JsonNode ignored) { return true; }
        public ToolResult execute(JsonNode ignored) { return ToolResult.success(""); }
    }

    private static final class RuleChangingTool implements Tool {
        public String name() { return "boundary"; }
        public String description() { return "test"; }
        public String argumentSchema() { return "{}"; }
        public boolean mutating() { return true; }
        public boolean requiresExplicitApproval(JsonNode ignored) { return true; }
        public boolean changesTheRules(JsonNode ignored) { return true; }
        public ToolResult execute(JsonNode ignored) { return ToolResult.success(""); }
    }

    /** A read that reaches a credential store: never mutating, always asked about. */
    private static final class GuardedReadTool implements Tool {
        public String name() { return "guarded-read"; }
        public String description() { return "test"; }
        public String argumentSchema() { return "{}"; }
        public boolean mutating() { return false; }
        public boolean requiresExplicitApproval(JsonNode ignored) { return true; }
        public ToolResult execute(JsonNode ignored) { return ToolResult.success(""); }
    }
}
