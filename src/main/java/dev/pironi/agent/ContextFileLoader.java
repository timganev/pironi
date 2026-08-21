package dev.pironi.agent;

import dev.pironi.model.ProviderType;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

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

        Path defaultHome = Path.of(System.getProperty("user.home"), ".pironi")
                .toAbsolutePath().normalize();
        List<String> sources = new ArrayList<>();
        String soul = loadPersonal
                ? readPersonalCascade(workspace, pironiHome, defaultHome, "SOUL.md", sources) : "";
        String user = loadPersonal
                ? readPersonalCascade(workspace, pironiHome, defaultHome, "USER.md", sources) : "";
        String project = readProjectInstructions(workspace);
        return new AgentContext(soul, user, project, String.join("\n", sources));
    }

    private static String readProjectInstructions(Workspace workspace) throws IOException {
        List<String> layers = new ArrayList<>();
        for (Path directory : workspaceLineage(workspace)) {
            Path candidate = directory.resolve("CLAUDE.md");
            if (!Files.exists(candidate)) continue;
            if (!Files.isRegularFile(candidate)) {
                throw new IOException("CLAUDE.md is not a regular file: " + candidate);
            }
            String content = Files.readString(candidate, StandardCharsets.UTF_8);
            int marker = content.indexOf(RUNTIME_CONTEXT_END);
            String runtimeContent =
                    marker < 0 ? content : content.substring(0, marker).stripTrailing();
            ensureWithinLimit(runtimeContent, candidate, "CLAUDE.md");
            if (!runtimeContent.isBlank()) layers.add(runtimeContent);
        }
        return combineLayers(layers, "CLAUDE.md");
    }

    /**
     * A file nearly the name we load, and on some machines exactly it: {@code soul.md} loads on
     * macOS and Windows and is a different file on Linux. Names are compared from a listing,
     * because {@code Files.exists} answers "yes" to either spelling on macOS and Windows.
     */
    private static String differentlyCasedSibling(Path home, String fileName) {
        if (!Files.isDirectory(home)) return "";
        try (var entries = Files.list(home)) {
            return entries
                    .map(entry -> entry.getFileName().toString())
                    .filter(name -> !name.equals(fileName) && name.equalsIgnoreCase(fileName))
                    .findFirst()
                    .map(name -> home.resolve(name)
                            + " (ignored: the loaded name is exactly " + fileName
                            + ", and on Linux this spelling is a different file)")
                    .orElse("");
        } catch (IOException e) {
            return "";
        }
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

    private static String readPersonalCascade(
            Workspace workspace,
            Path pironiHome,
            Path defaultHome,
            String fileName,
            List<String> sources
    ) throws IOException {
        LinkedHashSet<Path> homes = new LinkedHashSet<>();
        homes.add(defaultHome.toAbsolutePath().normalize());
        homes.add(pironiHome.toAbsolutePath().normalize());
        for (Path directory : workspaceLineage(workspace)) {
            homes.add(directory.resolve(".pironi").toAbsolutePath().normalize());
        }

        List<String> layers = new ArrayList<>();
        for (Path home : homes) {
            String content = readOptional(home.resolve(fileName), fileName);
            if (!content.isBlank()) {
                layers.add(content);
                sources.add(home.resolve(fileName).toString());
            } else {
                String nearMiss = differentlyCasedSibling(home, fileName);
                if (!nearMiss.isEmpty()) sources.add(nearMiss);
            }
        }
        return combineLayers(layers, fileName);
    }

    private static List<Path> workspaceLineage(Workspace workspace) {
        Path root = workspace.root().toAbsolutePath().normalize();
        Path userHome = canonicalize(Path.of(System.getProperty("user.home")));
        if (!root.startsWith(userHome)) {
            return List.of(root);
        }
        List<Path> lineage = new ArrayList<>();
        Path current = userHome;
        lineage.add(current);
        for (Path segment : userHome.relativize(root)) {
            current = current.resolve(segment);
            lineage.add(current);
        }
        return lineage;
    }

    private static Path canonicalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String combineLayers(List<String> layers, String label) throws IOException {
        if (layers.isEmpty()) return "";
        if (layers.size() == 1) return layers.getFirst();
        StringBuilder combined = new StringBuilder(
                "Layers are ordered from global to local. Later layers override earlier "
                        + "layers when instructions conflict."
        );
        for (int index = 0; index < layers.size(); index++) {
            combined.append("\n\n### ").append(label).append(" layer ")
                    .append(index + 1).append('\n').append(layers.get(index));
        }
        ensureWithinLimit(combined.toString(), Path.of(label), label + " cascade");
        return combined.toString();
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
