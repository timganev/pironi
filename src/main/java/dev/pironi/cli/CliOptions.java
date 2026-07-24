package dev.pironi.cli;

import dev.pironi.agent.PersonalContextMode;
import dev.pironi.model.ProviderType;
import dev.pironi.safety.ApprovalMode;
import dev.pironi.status.StatusMode;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
        boolean interactive,
        boolean noTui
) {
    private static final Path DEFAULT_WORKSPACE = Path.of("/home/tim/repos/pironi");
    private static final Set<String> BOOLEAN_FLAGS = Set.of("interactive", "no-interactive", "no-tui");

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
                interactive,
                false
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
                interactive,
                false
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
                interactive,
                false
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
        String task = values.get("task");
        boolean interactive = interactive(values);
        if (!interactive && (task == null || task.isBlank())) {
            throw new IllegalArgumentException("--task is required with --no-interactive");
        }
        Path workspace = Path.of(
                values.getOrDefault("workspace", DEFAULT_WORKSPACE.toString())
        ).toAbsolutePath().normalize();
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
        Path pironiHome = Path.of(values.getOrDefault(
                "pironi-home",
                Path.of(System.getProperty("user.home"), ".pironi").toString()
        )).toAbsolutePath().normalize();

        return new CliOptions(
                provider,
                baseUri,
                model,
                apiKey,
                keyEnvironmentName,
                workspace,
                task,
                ApprovalMode.parse(values.getOrDefault("approval", "read-only")),
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
                PersonalContextMode.parse(values.getOrDefault("personal-context", "auto")),
                StatusMode.parse(values.getOrDefault("status", "always")),
                values.get("verify-command"),
                interactive,
                false
        );
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
