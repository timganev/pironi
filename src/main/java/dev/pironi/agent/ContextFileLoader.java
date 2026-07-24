package dev.pironi.agent;

import dev.pironi.model.ProviderType;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ContextFileLoader {
    private static final int MAX_FILE_CHARACTERS = 24_000;
    private static final String RUNTIME_CONTEXT_END = "<!-- pironi-runtime-context-end -->";

    private ContextFileLoader() {
    }

    public static AgentContext load(
            Workspace workspace,
            ProviderType provider,
            PersonalContextMode personalContextMode,
            Path pironiHome
    ) throws IOException {
        boolean loadPersonal = switch (personalContextMode) {
            case ALLOW -> true;
            case DENY -> false;
            case AUTO -> provider == ProviderType.OLLAMA;
        };

        String soul = loadPersonal ? readOptional(pironiHome.resolve("SOUL.md"), "SOUL.md") : "";
        String user = loadPersonal ? readOptional(pironiHome.resolve("USER.md"), "USER.md") : "";
        String project = readProjectInstructions(workspace);
        return new AgentContext(soul, user, project);
    }

    private static String readProjectInstructions(Workspace workspace) throws IOException {
        Path candidate = workspace.root().resolve("CLAUDE.md");
        if (!Files.exists(candidate)) {
            return "";
        }
        Path path = workspace.resolveExisting("CLAUDE.md");
        String content = Files.readString(path, StandardCharsets.UTF_8);
        int marker = content.indexOf(RUNTIME_CONTEXT_END);
        String runtimeContent =
                marker < 0 ? content : content.substring(0, marker).stripTrailing();
        ensureWithinLimit(runtimeContent, path, "CLAUDE.md");
        return runtimeContent;
    }

    private static String readOptional(Path path, String label) throws IOException {
        if (!Files.exists(path)) {
            return "";
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException(label + " is not a regular file: " + path);
        }
        return readLimited(path, label);
    }

    private static String readLimited(Path path, String label) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        ensureWithinLimit(content, path, label);
        return content;
    }

    private static void ensureWithinLimit(String content, Path path, String label)
            throws IOException {
        if (content.length() > MAX_FILE_CHARACTERS) {
            throw new IOException(
                    label + " exceeds the " + MAX_FILE_CHARACTERS + " character limit: " + path
            );
        }
    }
}
