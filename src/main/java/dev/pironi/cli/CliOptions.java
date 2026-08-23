package dev.pironi.cli;

import dev.pironi.agent.PersonalContextMode;
import dev.pironi.model.ProviderType;
import dev.pironi.safety.ApprovalMode;
import dev.pironi.status.StatusMode;
import dev.pironi.tool.ShellScope;

import java.net.URI;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;

record CliOptions(
        ProviderType provider,
        URI baseUri,
        String model,
        String apiKey,
        String apiKeyEnvironmentName,
        Path workspace,
        String task,
        ApprovalMode approvalMode,
        int maxTurns,
        int contextSize,
        int maxOutputTokens,
        Duration modelTimeout,
        Path tracePath,
        Path pironiHome,
        PersonalContextMode personalContextMode,
        StatusMode statusMode,
        String verifyCommand,
        Set<String> denyTools,
        Set<String> allowTools,
        ShellScope shellScope,
        ShellScope readScope,
        String resumeSession,
        List<Path> searchRoots,
        boolean interactive,
        boolean noTui,
        int maxSubagents,
        int subagentTimeoutSeconds
) {
    private static final Path DEFAULT_WORKSPACE = Path.of(
            System.getProperty("user.dir", ".")
    ).toAbsolutePath().normalize();
    private static final Set<String> BOOLEAN_FLAGS = Set.of(
            "interactive", "no-interactive", "no-tui", "continue", "portable");
    private static final Set<String> VALUE_OPTIONS = Set.of(
            "provider", "base-url", "api-key-env", "model", "workspace", "task", "task-file",
            "approval", "activity", "max-turns", "context", "max-output-tokens",
            "timeout-seconds", "trace", "pironi-home", "personal-context", "status",
            "verify-command", "deny-tools", "allow-tools", "shell-scope", "read-scope",
            "search-roots", "resume",
            "max-subagents", "subagent-timeout-seconds"
    );
    private static final Set<String> KNOWN_OPTIONS = java.util.stream.Stream.concat(
            BOOLEAN_FLAGS.stream(), VALUE_OPTIONS.stream()
    ).collect(java.util.stream.Collectors.toUnmodifiableSet());

    CliOptions withModel(String newModel) {
        return new CliOptions(
                provider,
                baseUri,
                newModel,
                apiKey,
                apiKeyEnvironmentName,
                workspace,
                task,
                approvalMode,
                maxTurns,
                contextSize,
                maxOutputTokens,
                modelTimeout,
                tracePath,
                pironiHome,
                personalContextMode,
                statusMode,
                verifyCommand,
                denyTools,
                allowTools,
                shellScope,
                readScope,
                resumeSession,
                searchRoots,
                interactive,
                noTui,
                maxSubagents,
                subagentTimeoutSeconds
        );
    }

    CliOptions withProviderModel(
            ProviderType newProvider,
            URI newBaseUri,
            String newModel,
            String newApiKey,
            String newApiKeyEnvironmentName,
            int newContextSize
    ) {
        return new CliOptions(
                newProvider,
                newBaseUri,
                newModel,
                newApiKey,
                newApiKeyEnvironmentName,
                workspace,
                task,
                approvalMode,
                maxTurns,
                newContextSize,
                maxOutputTokens,
                modelTimeout,
                tracePath,
                pironiHome,
                personalContextMode,
                statusMode,
                verifyCommand,
                denyTools,
                allowTools,
                shellScope,
                readScope,
                resumeSession,
                searchRoots,
                interactive,
                noTui,
                maxSubagents,
                subagentTimeoutSeconds
        );
    }

    CliOptions withWorkspace(java.nio.file.Path newWorkspace) {
        return new CliOptions(
                provider,
                baseUri,
                model,
                apiKey,
                apiKeyEnvironmentName,
                newWorkspace,
                task,
                approvalMode,
                maxTurns,
                contextSize,
                maxOutputTokens,
                modelTimeout,
                tracePath,
                pironiHome,
                personalContextMode,
                statusMode,
                verifyCommand,
                denyTools,
                allowTools,
                shellScope,
                readScope,
                resumeSession,
                searchRoots,
                interactive,
                noTui,
                maxSubagents,
                subagentTimeoutSeconds
        );
    }

    CliOptions withApprovalMode(ApprovalMode newApprovalMode) {
        return new CliOptions(
                provider,
                baseUri,
                model,
                apiKey,
                apiKeyEnvironmentName,
                workspace,
                task,
                newApprovalMode,
                maxTurns,
                contextSize,
                maxOutputTokens,
                modelTimeout,
                tracePath,
                pironiHome,
                personalContextMode,
                statusMode,
                verifyCommand,
                denyTools,
                allowTools,
                shellScope,
                readScope,
                resumeSession,
                searchRoots,
                interactive,
                noTui,
                maxSubagents,
                subagentTimeoutSeconds
        );
    }

    static CliOptions parse(String[] args, Map<String, String> environment) {
        return parse(
                args,
                environment,
                Path.of(System.getProperty("user.home"), ".hermes", ".env")
        );
    }

    static CliOptions parse(
            String[] args,
            Map<String, String> environment,
            Path hermesEnvironmentFile
    ) {
        Map<String, String> values = parsePairs(args);
        if (values.containsKey("help")) {
            throw new HelpRequested();
        }

        String providerValue = values.getOrDefault("provider", "ollama");
        ProviderType provider = ProviderType.parse(providerValue);
        String model = values.get("model");
        if (model == null || model.isBlank()) {
            if (provider == ProviderType.DEEPSEEK) {
                model = "deepseek-v4-pro";
            } else if (provider == ProviderType.OPENROUTER) {
                model = "openrouter/auto";
            } else if (provider == ProviderType.OLLAMA) {
                model = "qwen3.6:35b-a3b";
            } else {
                throw new IllegalArgumentException("Missing required option --model");
            }
        }
        if (values.containsKey("task") && values.containsKey("task-file")) {
            throw new IllegalArgumentException("--task and --task-file cannot be used together");
        }
        String task = values.containsKey("task-file")
                ? readTaskFile(values.get("task-file"))
                : values.get("task");
        if (!values.containsKey("task-file") && looksMangledByConsoleEncoding(task)) {
            throw new IllegalArgumentException(
                    "--task lost characters to the console code page ("
                            + System.getProperty("sun.jnu.encoding", "unknown")
                            + "): the text arrived as \"" + task + "\". Command-line arguments are "
                            + "decoded before the JVM starts, so this cannot be recovered here. "
                            + "Save the task as UTF-8 and pass --task-file instead.");
        }
        boolean interactive = interactive(values);
        if (!interactive && (task == null || task.isBlank())) {
            throw new IllegalArgumentException("--task or --task-file is required with --no-interactive");
        }
        Path workspace = Path.of(values.getOrDefault(
                "workspace",
                environment.getOrDefault("PIRONI_DEFAULT_WORKSPACE", DEFAULT_WORKSPACE.toString())
        )).toAbsolutePath().normalize();
        URI baseUri = URI.create(values.getOrDefault("base-url", defaultBaseUrl(providerValue)));

        String apiKey = null;
        String keyEnvironmentName = values.getOrDefault(
                "api-key-env",
                defaultApiKeyEnvironment(provider)
        );
        if (provider != ProviderType.OLLAMA) {
            Path fallback = usesHermesEnvironmentFallback(provider, keyEnvironmentName)
                    ? hermesEnvironmentFile
                    : null;
            apiKey = ApiKeyResolver.resolve(environment, keyEnvironmentName, fallback);
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException(
                        "Missing API key in environment variable " + keyEnvironmentName
                );
            }
        }

        Path trace = values.containsKey("trace")
                ? Path.of(values.get("trace"))
                : workspace.resolve(".pironi/trace.jsonl");
        // A release keeps its skills, sessions and memory in ~/.pironi like every other way of
        // running Pironi, so upgrading is unzipping a new folder rather than starting over.
        // --portable puts all of it inside the bundle instead, for a copy on a stick that leaves
        // nothing behind; the launcher says where the bundle is, and only it can answer that.
        String bundle = environment.get("PIRONI_BUNDLE_DIR");
        String portableHome = values.containsKey("portable") && bundle != null && !bundle.isBlank()
                ? Path.of(bundle).resolve(".pironi").toString() : null;
        Path pironiHome = Path.of(values.getOrDefault(
                "pironi-home",
                portableHome != null ? portableHome : environment.getOrDefault(
                        "PIRONI_DEFAULT_HOME",
                        Path.of(System.getProperty("user.home"), ".pironi").toString()
                )
        )).toAbsolutePath().normalize();

        ApprovalMode approvalMode = ApprovalMode.parse(
                values.getOrDefault("approval", "read-only")
        );
        String activity = values.get("activity");
        if (activity != null) {
            if (!activity.equalsIgnoreCase("auto")) {
                throw new IllegalArgumentException(
                        "Unknown activity mode: " + activity + " (expected auto)"
                );
            }
            approvalMode = ApprovalMode.AUTO;
        }

        Set<String> denied = parseToolSet(values.get("deny-tools"));
        Set<String> allowed = parseToolSet(values.get("allow-tools"));
        if (!denied.isEmpty() && !allowed.isEmpty()) {
            throw new IllegalArgumentException("--allow-tools and --deny-tools cannot be used together");
        }
        List<Path> searchRoots = parseSearchRoots(
                values.getOrDefault("search-roots", environment.get("PIRONI_DEFAULT_SEARCH_ROOTS")),
                workspace
        );

        return new CliOptions(
                provider,
                baseUri,
                model,
                apiKey,
                keyEnvironmentName,
                workspace,
                task,
                approvalMode,
                positiveInt(values, "max-turns", 8),
                positiveInt(
                        values,
                        "context",
                        defaultContextSize(provider)
                ),
                positiveInt(values, "max-output-tokens", 4_096),
                Duration.ofSeconds(positiveInt(values, "timeout-seconds", 600)),
                trace,
                pironiHome,
                // SOUL.md and USER.md load unless someone says otherwise. "auto" withheld them
                // from every provider but Ollama, which meant the agent forgot who it was talking
                // to the moment you pointed it at a hosted model - the case people actually run.
                // Withholding is still one flag away: --personal-context auto, or deny.
                PersonalContextMode.parse(values.getOrDefault(
                        "personal-context",
                        environment.getOrDefault("PIRONI_DEFAULT_PERSONAL_CONTEXT", "allow")
                )),
                StatusMode.parse(values.getOrDefault("status", "auto")),
                values.get("verify-command"),
                denied,
                allowed,
                ShellScope.parse(values.getOrDefault(
                        "shell-scope",
                        environment.getOrDefault("PIRONI_DEFAULT_SHELL_SCOPE", "workspace")
                )),
                // Reading is not what does damage; writing is.
                ShellScope.parse(values.getOrDefault(
                        "read-scope",
                        environment.getOrDefault("PIRONI_DEFAULT_READ_SCOPE", "unrestricted")
                )),
                // --continue is --resume with the id left to the workspace's own history, which
                // is the common case: a crashed headless run printed its session id and then had
                // no way to use it, because /resume exists only in the interactive shell.
                values.containsKey("continue") ? "" : values.get("resume"),
                searchRoots,
                interactive,
                values.containsKey("no-tui"),
                positiveInt(values, "max-subagents", 2),
                positiveInt(values, "subagent-timeout-seconds", 120)
        );
    }

    private static Set<String> parseToolSet(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (String name : raw.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return Set.copyOf(names);
    }

    private static String readTaskFile(String value) {
        Path path = Path.of(value).toAbsolutePath().normalize();
        try {
            return ByteOrderMark.stripped(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read --task-file " + path + ": " + e.getMessage());
        }
    }

    /**
     * Whether a task arrived with its non-ASCII characters already replaced by question marks.
     *
     * <p>On Windows the launcher decodes arguments with the ANSI code page, so Cyrillic passed to
     * {@code --task} reaches the JVM as {@code ???????} and the model is asked the wrong question.
     * Two question marks in a row are what survives that; one is ordinary punctuation.
     */
    static boolean looksMangledByConsoleEncoding(String task) {
        if (task == null || task.isBlank()) return false;
        String encoding = System.getProperty("sun.jnu.encoding", "");
        if (encoding.equalsIgnoreCase("UTF-8")) return false;
        return task.contains("??") || task.indexOf('�') >= 0;
    }

    private static List<Path> parseSearchRoots(String raw, Path workspace) {
        if (raw == null || raw.isBlank()) return List.of(workspace);
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
    }

    private static Map<String, String> parsePairs(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (argument.equals("--help") || argument.equals("-h")) {
                values.put("help", "true");
                continue;
            }
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + argument);
            }
            String key = argument.substring(2);
            if (!KNOWN_OPTIONS.contains(key)) {
                throw new IllegalArgumentException(unknownOptionMessage(key));
            }
            if (BOOLEAN_FLAGS.contains(key)) {
                if (values.put(key, "true") != null) {
                    throw new IllegalArgumentException("Duplicate option: --" + key);
                }
                continue;
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for --" + key);
            }
            if (values.put(key, args[++i]) != null) {
                throw new IllegalArgumentException("Duplicate option: --" + key);
            }
        }
        return values;
    }

    private static String unknownOptionMessage(String key) {
        String suggestion = KNOWN_OPTIONS.stream()
                .min(java.util.Comparator.comparingInt(candidate -> editDistance(key, candidate)))
                .filter(candidate -> editDistance(key, candidate) <= 3)
                .map(candidate -> ". Did you mean --" + candidate + "?")
                .orElse("");
        return "Unknown option: --" + key + suggestion;
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int replace = previous[j - 1]
                        + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), replace);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static boolean interactive(Map<String, String> values) {
        if (values.containsKey("interactive") && values.containsKey("no-interactive")) {
            throw new IllegalArgumentException(
                    "--interactive and --no-interactive cannot be used together"
            );
        }
        return !values.containsKey("no-interactive");
    }

    private static String defaultBaseUrl(String provider) {
        return switch (provider.toLowerCase()) {
            case "deepseek" -> "https://api.deepseek.com";
            case "openrouter" -> "https://openrouter.ai/api/v1";
            default -> "http://127.0.0.1:11434";
        };
    }

    private static String defaultApiKeyEnvironment(ProviderType provider) {
        return switch (provider) {
            case DEEPSEEK -> "DEEPSEEK_API_KEY";
            case OPENROUTER -> "OPENROUTER_API_KEY";
            default -> "OPENAI_API_KEY";
        };
    }

    private static boolean usesHermesEnvironmentFallback(
            ProviderType provider,
            String environmentName
    ) {
        return (provider == ProviderType.DEEPSEEK
                && environmentName.equals("DEEPSEEK_API_KEY"))
                || (provider == ProviderType.OPENROUTER
                && environmentName.equals("OPENROUTER_API_KEY"));
    }

    private static int defaultContextSize(ProviderType provider) {
        return switch (provider) {
            case DEEPSEEK -> 1_000_000;
            case OPENROUTER -> 200_000;
            default -> 8_192;
        };
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option --" + name);
        }
        return value;
    }

    private static int positiveInt(
            Map<String, String> values,
            String name,
            int defaultValue
    ) {
        String raw = values.get(name);
        if (raw == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a positive integer");
        }
    }

    static final class HelpRequested extends RuntimeException {
    }
}
