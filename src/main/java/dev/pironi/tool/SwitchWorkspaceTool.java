package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Asks to move the workspace instead of telling the user to type the command. The decision stays
 * human - a document must not talk the model into writing elsewhere - but the agent proposes the
 * directory and one keypress settles it. Registered only where a prompt can be answered.
 */
public final class SwitchWorkspaceTool implements Tool {
    private final Workspace workspace;
    private volatile Consumer<Path> onSwitch = path -> { };

    public SwitchWorkspaceTool(Workspace workspace) {
        this.workspace = workspace;
    }

    /** Wired after startup: the switch has to reach the grants, the saved session and the model. */
    public void onSwitch(Consumer<Path> callback) {
        if (callback != null) this.onSwitch = callback;
    }

    @Override public String name() { return "switch_workspace"; }

    @Override public String description() {
        return "Ask the user to move the workspace to another directory, which grants reading and "
                + "writing there for the rest of the session. Use this when the work is outside "
                + "the current workspace instead of asking the user to type a command. The user "
                + "confirms every move; directories left behind stay readable.";
    }

    @Override public String argumentSchema() {
        return "{\"path\":\"string, required, absolute directory\"}";
    }

    @Override public boolean mutating() { return true; }

    /** Never auto-approved, whatever the approval mode: the human owns this one. */
    @Override public boolean requiresExplicitApproval(JsonNode arguments) { return true; }

    @Override public String approvalPreview(JsonNode arguments) {
        try {
            Path target = requested(arguments);
            return "/workspace " + target
                    + "\n  reading and writing move there; " + workspace.root() + " stays readable";
        } catch (IllegalArgumentException e) {
            return "Invalid workspace request: " + e.getMessage();
        }
    }

    @Override public ToolResult validate(JsonNode arguments) {
        try {
            check(requested(arguments));
            return ToolResult.success("validated");
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    @Override public ToolResult execute(JsonNode arguments) {
        try {
            Path target = requested(arguments);
            check(target);
            Path previous = workspace.root();
            Path moved = workspace.switchTo(target);
            if (moved.equals(previous)) return ToolResult.success("Already the workspace: " + moved);
            onSwitch.accept(moved);
            return ToolResult.success("Workspace moved to " + moved
                    + ". Reading, writing and run_command act there now; " + previous
                    + " stays readable. Paths are relative to the new workspace.");
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    private Path requested(JsonNode arguments) {
        String path = ToolArguments.requiredText(arguments, "path").trim();
        String home = System.getProperty("user.home", "");
        if (path.equals("~")) return Path.of(home);
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return Path.of(home, path.substring(2));
        }
        return Path.of(path);
    }

    private void check(Path target) throws IOException {
        if (!Files.exists(target)) {
            // Creating it silently would turn a typo into an empty workspace, and the agent
            // would then report the project as empty rather than as misspelt.
            throw new IOException("No such directory: " + target);
        }
        if (!Files.isDirectory(target)) throw new IOException("Not a directory: " + target);
    }
}
