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
    /**
     * Tools the user has said not to ask about again. Only the waivable prompts put a name here:
     * reaching a credential store is asked every time, whatever has been said before.
     */
    private final java.util.Set<String> notAskingAgain =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

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
                return request(toolName, preview, false);
            }

            @Override
            public String request(String toolName, String preview, boolean waivable)
                    throws IOException {
                output.printf("Allow tool '%s'?%n%s%n%s ", toolName, preview,
                        waivable ? "[y/N/a=always]" : "[y/N]");
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
            // Auto means auto. A shell command used to be confirmed here even under this mode, on
            // the reasoning that its effect cannot be read off its name - which is true, and is
            // exactly what the person choosing this mode has accepted. Asking anyway made a run of
            // twenty commands twenty prompts, and made the documented meaning of the flag false.
            case AUTO -> tool.changesTheRules(arguments)
                    ? ruleChangeDecision(tool, arguments)
                    : targetsProtectedContext(arguments)
                            ? protectedContextDecision(tool, arguments) : ApprovalDecision.ALLOW;
            case READ_ONLY -> ApprovalDecision.DENY;
            case ASK -> prompt(tool, arguments, true);
        };
    }

    /** Never waivable: this is the credential-store path, and it is asked every single time. */
    private ApprovalDecision explicitActionDecision(Tool tool, JsonNode arguments) {
        if (promptAllowed) return prompt(tool, arguments, false);
        interaction.result(
                "Denied: this action requires explicit approval in an interactive session."
        );
        return ApprovalDecision.DENY;
    }

    /** Not waivable either: the point of the question is that the boundary is moving. */
    private ApprovalDecision ruleChangeDecision(Tool tool, JsonNode arguments) {
        if (promptAllowed) return prompt(tool, arguments, false);
        interaction.result(
                "Denied: changing where the session may act requires explicit approval in an "
                        + "interactive session."
        );
        return ApprovalDecision.DENY;
    }

    private ApprovalDecision protectedContextDecision(Tool tool, JsonNode arguments) {
        if (promptAllowed) return prompt(tool, arguments, false);
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

    /**
     * @param waivable whether "always" is on offer here. It is not on the paths that guard
     *                 credentials and the agent's own context files, however often they come up.
     */
    private ApprovalDecision prompt(Tool tool, JsonNode arguments, boolean waivable) {
        if (waivable && notAskingAgain.contains(tool.name())) return ApprovalDecision.ALLOW;
        try {
            Interaction currentInteraction = interaction;
            String answer = currentInteraction.request(
                    tool.name(), tool.approvalPreview(arguments), waivable
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
                case "a", "all", "always", "в", "винаги" -> {
                    if (waivable) {
                        notAskingAgain.add(tool.name());
                        currentInteraction.result(
                                "Approved. Not asking about '" + tool.name()
                                        + "' again this session."
                        );
                    } else {
                        // Saying "always" here is not refused - it plainly means yes - but it
                        // cannot be granted, and saying so beats asking again with no explanation.
                        currentInteraction.result(
                                "Approved this once. Reaching credentials or the agent's own "
                                        + "context files is asked every time and cannot be waived."
                        );
                    }
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

        /**
         * @param waivable whether to offer "always" as an answer. Implementations that do not
         *                 override this simply never offer it, which is the safe direction.
         */
        default String request(String toolName, String preview, boolean waivable)
                throws IOException {
            return request(toolName, preview);
        }

        void result(String message);
    }
}
