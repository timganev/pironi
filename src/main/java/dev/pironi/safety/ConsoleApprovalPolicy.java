package dev.pironi.safety;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.tool.Tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ConsoleApprovalPolicy implements ApprovalPolicy {
    private static final Pattern PROTECTED_CONTEXT = Pattern.compile(
            "(?i)(?:^|[/\\\\\"'\\s:])(?:SOUL|USER|CLAUDE)\\.md(?:$|[/\\\\\"'\\s])"
    );
    private volatile ApprovalMode mode;
    private volatile Interaction interaction;
    private final boolean promptAllowed;

    public ConsoleApprovalPolicy(ApprovalMode mode, BufferedReader input, PrintStream output) {
        this(mode, input, output, true);
    }

    public ConsoleApprovalPolicy(
            ApprovalMode mode,
            BufferedReader input,
            PrintStream output,
            boolean promptAllowed
    ) {
        this.mode = mode;
        this.promptAllowed = promptAllowed;
        this.interaction = new Interaction() {
            @Override
            public String request(String toolName, String preview) throws IOException {
                output.printf("Allow tool '%s'?%n%s%n[y/N] ", toolName, preview);
                output.flush();
                return input.readLine();
            }

            @Override
            public void result(String message) {
                output.println(message);
            }
        };
    }

    public ApprovalMode mode() {
        return mode;
    }

    public void updateMode(ApprovalMode mode) {
        this.mode = java.util.Objects.requireNonNull(mode, "mode");
    }

    public void updateInteraction(Interaction interaction) {
        this.interaction = java.util.Objects.requireNonNull(interaction, "interaction");
    }

    @Override
    public ApprovalDecision decide(Tool tool, JsonNode arguments) {
        // A read can need asking too.
        if (!tool.mutating(arguments) && !tool.requiresExplicitApproval(arguments)) {
            return ApprovalDecision.ALLOW;
        }
        if (!tool.mutating(arguments)) {
            return explicitActionDecision(tool, arguments);
        }
        return switch (mode) {
            case AUTO -> tool.requiresExplicitApproval(arguments)
                    ? explicitActionDecision(tool, arguments)
                    : targetsProtectedContext(arguments)
                            ? protectedContextDecision(tool, arguments) : ApprovalDecision.ALLOW;
            case READ_ONLY -> ApprovalDecision.DENY;
            case ASK -> prompt(tool, arguments);
        };
    }

    private ApprovalDecision explicitActionDecision(Tool tool, JsonNode arguments) {
        if (promptAllowed) return prompt(tool, arguments);
        interaction.result(
                "Denied: this action requires explicit approval in an interactive session."
        );
        return ApprovalDecision.DENY;
    }

    private ApprovalDecision protectedContextDecision(Tool tool, JsonNode arguments) {
        if (promptAllowed) return prompt(tool, arguments);
        interaction.result(
                "Denied: persistent context files require explicit approval in an "
                        + "interactive session."
        );
        return ApprovalDecision.DENY;
    }

    private static boolean targetsProtectedContext(JsonNode arguments) {
        return arguments != null
                && PROTECTED_CONTEXT.matcher(arguments.toString()).find();
    }

    private ApprovalDecision prompt(Tool tool, JsonNode arguments) {
        try {
            Interaction currentInteraction = interaction;
            String answer = currentInteraction.request(
                    tool.name(), tool.approvalPreview(arguments)
            );
            if (answer == null) {
                if (System.console() == null) {
                    currentInteraction.result(
                            "Denied: --approval ask requires an interactive terminal. "
                                    + "Use --approval auto or --approval read-only."
                    );
                } else {
                    currentInteraction.result("Denied: approval input reached EOF.");
                }
                return ApprovalDecision.DENY;
            }
            return switch (answer.strip().toLowerCase(Locale.ROOT)) {
                case "y", "yes", "д", "да" -> {
                    currentInteraction.result("Approved.");
                    yield ApprovalDecision.ALLOW;
                }
                default -> {
                    currentInteraction.result("Denied.");
                    yield ApprovalDecision.DENY;
                }
            };
        } catch (IOException e) {
            interaction.result("Denied: cannot read approval input: " + e.getMessage());
            return ApprovalDecision.DENY;
        }
    }

    public interface Interaction {
        String request(String toolName, String preview) throws IOException;

        void result(String message);
    }
}
