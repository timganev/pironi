package dev.pironi.safety;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.tool.Tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;

public final class ConsoleApprovalPolicy implements ApprovalPolicy {
    private volatile ApprovalMode mode;
    private final BufferedReader input;
    private final PrintStream output;

    public ConsoleApprovalPolicy(ApprovalMode mode, BufferedReader input, PrintStream output) {
        this.mode = mode;
        this.input = input;
        this.output = output;
    }

    public ApprovalMode mode() {
        return mode;
    }

    public void updateMode(ApprovalMode mode) {
        this.mode = java.util.Objects.requireNonNull(mode, "mode");
    }

    @Override
    public ApprovalDecision decide(Tool tool, JsonNode arguments) {
        if (!tool.mutating()) {
            return ApprovalDecision.ALLOW;
        }
        return switch (mode) {
            case AUTO -> ApprovalDecision.ALLOW;
            case READ_ONLY -> ApprovalDecision.DENY;
            case ASK -> prompt(tool, arguments);
        };
    }

    private ApprovalDecision prompt(Tool tool, JsonNode arguments) {
        output.printf(
                "Allow tool '%s'?%n%s%n[y/N] ",
                tool.name(),
                tool.approvalPreview(arguments)
        );
        output.flush();
        try {
            String answer = input.readLine();
            if (answer == null) {
                if (System.console() == null) {
                    output.println(
                            "Denied: --approval ask requires an interactive terminal. "
                                    + "Use --approval auto or --approval read-only."
                    );
                } else {
                    output.println("Denied: approval input reached EOF.");
                }
                return ApprovalDecision.DENY;
            }
            return switch (answer.strip().toLowerCase(Locale.ROOT)) {
                case "y", "yes", "д", "да" -> {
                    output.println("Approved.");
                    yield ApprovalDecision.ALLOW;
                }
                default -> {
                    output.println("Denied.");
                    yield ApprovalDecision.DENY;
                }
            };
        } catch (IOException e) {
            output.println("Denied: cannot read approval input: " + e.getMessage());
            return ApprovalDecision.DENY;
        }
    }
}
