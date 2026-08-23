package dev.pironi.tool;

public record ToolResult(boolean success, String output) {
    public ToolResult {
        output = output == null ? "" : output;
    }

    public static ToolResult success(String output) {
        return new ToolResult(true, output);
    }

    public static ToolResult failure(String output) {
        return new ToolResult(false, output);
    }

    /** Prefer this to {@code failure(e.getMessage())}: see {@link ToolFailure} for why. */
    public static ToolResult failure(Throwable failure) {
        return new ToolResult(false, ToolFailure.describe(failure));
    }
}
