package dev.pironi.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.agent.AgentLoop;
import dev.pironi.agent.AgentResult;
import dev.pironi.agent.ContextFileLoader;
import dev.pironi.agent.DecisionParser;
import dev.pironi.agent.CapabilityReport;
import dev.pironi.model.ProviderConfig;
import dev.pironi.model.SwitchableModelClient;
import dev.pironi.safety.ConsoleApprovalPolicy;
import dev.pironi.safety.CheckpointManager;
import dev.pironi.safety.Workspace;
import dev.pironi.tool.ApplyPatchTool;
import dev.pironi.tool.ListFilesTool;
import dev.pironi.tool.HttpGetTool;
import dev.pironi.tool.FindFilesTool;
import dev.pironi.tool.MoveFileTool;
import dev.pironi.tool.ReadFileTool;
import dev.pironi.tool.RunCommandTool;
import dev.pironi.tool.RollbackCheckpointTool;
import dev.pironi.tool.Tool;
import dev.pironi.tool.ToolRegistry;
import dev.pironi.tool.WriteFileTool;
import dev.pironi.trace.JsonlTraceWriter;
import dev.pironi.status.NoOpStatusReporter;
import dev.pironi.status.StatusReporter;
import dev.pironi.status.StatusMode;
import dev.pironi.status.TerminalStatusReporter;
import dev.pironi.verification.ProjectVerificationGate;
import dev.pironi.session.SessionStore;
import dev.pironi.session.SkillStore;
import dev.pironi.session.PersistentAgentMemory;
import dev.pironi.session.ContextCompressor;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class PironiMain {
    private PironiMain() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            if (List.of(args).contains("--help") || List.of(args).contains("-h")) {
                printUsage();
                System.exit(0);
            }
            if (List.of(args).contains("--test-keys")) {
                // Extract optional script file after --test-keys
                String scriptFile = null;
                for (int i = 0; i < args.length; i++) {
                    if ("--test-keys".equals(args[i]) && i + 1 < args.length) {
                        scriptFile = args[i + 1];
                        break;
                    }
                }
                TestKeysRunner.main(scriptFile != null ? new String[]{scriptFile} : new String[0]);
                return;
            }
            exitCode = run(args);
        } catch (CliOptions.HelpRequested ignored) {
            printUsage();
            exitCode = 0;
        } catch (IllegalArgumentException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.err.println("Use --help for usage.");
            exitCode = 2;
        } catch (Exception e) {
            System.err.println("Pironi failed: " + e.getMessage());
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    static int run(String[] args) throws Exception {
        LastSessionStore lastSession = new LastSessionStore(
                Path.of(System.getProperty("user.home"), ".pironi", "last-session.properties")
        );
        String[] effectiveArguments = args.length == 0 ? lastSession.loadArguments() : args;
        CliOptions options = CliOptions.parse(effectiveArguments, System.getenv());
        ObjectMapper objectMapper = new ObjectMapper();
        Workspace workspace = new Workspace(options.workspace());
        lastSession.save(options);
        CheckpointManager checkpoints = new CheckpointManager(workspace);
        lastSession.save(options);

        // Memory stores (L2, L3, L4)
        SessionStore sessions = new SessionStore(options.pironiHome(), objectMapper);
        ContextCompressor compressor = new ContextCompressor(options.contextSize(), objectMapper);
        SkillStore skills = new SkillStore(options.pironiHome());
        PersistentAgentMemory memory = new PersistentAgentMemory(
                sessions, compressor, skills, objectMapper, options.model(),
                options.workspace(), options.contextSize(), options.maxTurns()
        );
        ProviderConfig provider = new ProviderConfig(
                options.provider(),
                options.baseUri(),
                options.model(),
                options.apiKey(),
                options.modelTimeout(),
                options.contextSize(),
                options.maxOutputTokens()
        );
        SwitchableModelClient modelClient = new SwitchableModelClient(
                options.model(),
                provider.createClient()
        );
        AtomicReference<CliOptions> currentOptions = new AtomicReference<>(options);
        ProviderModelCatalog modelCatalog = new ProviderModelCatalog(
                java.net.http.HttpClient.newHttpClient(), objectMapper
        );

        List<Tool> availableTools = List.of(
                new ListFilesTool(workspace, 500),
                new ReadFileTool(workspace, 32_000),
                new WriteFileTool(workspace),
                new ApplyPatchTool(workspace, checkpoints),
                new MoveFileTool(workspace, checkpoints),
                new RollbackCheckpointTool(checkpoints),
                new FindFilesTool(options.searchRoots()),
                new HttpGetTool(),
                new RunCommandTool(
                        workspace, Duration.ofSeconds(90), 32_000, options.shellScope()
                )
        );
        ToolRegistry tools = configuredTools(
                availableTools, options.denyTools(), options.allowTools()
        );

        boolean statusEnabled = options.statusMode() == StatusMode.ALWAYS
                || (options.statusMode() == StatusMode.AUTO && System.console() != null);
        boolean interactive = options.interactive() && !options.noTui();

        Terminal terminal = null;
        if (interactive) {
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .build();
        }

        StatusReporter status;
        if (statusEnabled) {
            if (terminal != null) {
                status = new TerminalStatusReporter(
                        options.model(),
                        options.workspace(),
                        options.contextSize(),
                        options.maxTurns(),
                        System.out,
                        terminal
                );
            } else {
                status = new TerminalStatusReporter(
                        options.model(),
                        options.workspace(),
                        options.contextSize(),
                        options.maxTurns(),
                        System.out
                );
            }
        } else {
            status = new NoOpStatusReporter();
        }

        try (
                var trace = new JsonlTraceWriter(options.tracePath(), objectMapper);
                var input = new BufferedReader(new InputStreamReader(System.in));
                status
        ) {
            var agentContext = ContextFileLoader.load(
                    workspace,
                    options.provider(),
                    options.personalContextMode(),
                    options.pironiHome()
            );
            agentContext.updateRuntimeSession(runtimeSessionDescription(options));
            CapabilityReport capabilityReport = new CapabilityReport(tools, agentContext);
            RuntimeDoctor runtimeDoctor = new RuntimeDoctor(
                    options.workspace(), options.pironiHome(), capabilityReport
            );
            ConsoleApprovalPolicy approvalPolicy = new ConsoleApprovalPolicy(
                    options.approvalMode(),
                    input,
                    System.out
            );
            java.util.function.Consumer<String> liveOutput = null;
            if (interactive) {
                Terminal outputTerminal = terminal;
                liveOutput = chunk -> {
                    synchronized (outputTerminal) {
                        outputTerminal.writer().print(new org.jline.utils.AttributedString(
                                chunk,
                                org.jline.utils.AttributedStyle.DEFAULT.foreground(
                                        org.jline.utils.AttributedStyle.GREEN
                                )
                        ).toAnsi(outputTerminal));
                        outputTerminal.flush();
                    }
                };
            }
            AgentLoop loop = new AgentLoop(
                    modelClient,
                    new DecisionParser(objectMapper),
                    objectMapper,
                    tools,
                    approvalPolicy,
                    trace,
                    agentContext,
                    status,
                    new ProjectVerificationGate(
                            workspace,
                            options.verifyCommand(),
                            Duration.ofSeconds(300)
                    ),
                    options.maxTurns(),
                    4,
                    liveOutput,
                    memory
            );

            if (interactive) {
                InteractiveShell.ModelCommands modelCommands =
                        new InteractiveShell.ModelCommands() {
                            @Override
                            public String currentProvider() {
                                return currentOptions.get().provider()
                                        .name().toLowerCase().replace('_', '-');
                            }

                            @Override
                            public String currentModel() {
                                return modelClient.model();
                            }

                            @Override
                            public void switchModel(String model) throws java.io.IOException {
                                CliOptions previous = currentOptions.get();
                                CliOptions updated = switchedOptions(
                                        previous,
                                        model,
                                        System.getenv(),
                                        Path.of(
                                                System.getProperty("user.home"),
                                                ".hermes",
                                                ".env"
                                        )
                                );
                                ProviderConfig updatedProvider = new ProviderConfig(
                                        updated.provider(),
                                        updated.baseUri(),
                                        updated.model(),
                                        updated.apiKey(),
                                        updated.modelTimeout(),
                                        updated.contextSize(),
                                        updated.maxOutputTokens()
                                );
                                modelClient.switchTo(model, updatedProvider.createClient());
                                currentOptions.set(updated);
                                lastSession.save(updated);
                                agentContext.updateRuntimeSession(
                                        runtimeSessionDescription(updated)
                                );
                                status.configurationChanged(model, updated.contextSize());
                            }

                            @Override
                            public void switchModel(String provider, String model)
                                    throws java.io.IOException {
                                switchTo(switchedOptions(
                                        currentOptions.get(), provider, model, System.getenv(),
                                        Path.of(System.getProperty("user.home"), ".hermes", ".env")
                                ));
                            }

                            private void switchTo(CliOptions updated) throws java.io.IOException {
                                ProviderConfig updatedProvider = new ProviderConfig(
                                        updated.provider(), updated.baseUri(), updated.model(),
                                        updated.apiKey(), updated.modelTimeout(), updated.contextSize(),
                                        updated.maxOutputTokens()
                                );
                                modelClient.switchTo(updated.model(), updatedProvider.createClient());
                                currentOptions.set(updated);
                                lastSession.save(updated);
                                agentContext.updateRuntimeSession(runtimeSessionDescription(updated));
                                status.configurationChanged(updated.model(), updated.contextSize());
                            }

                            @Override
                            public String currentApproval() {
                                return approvalPolicy.mode()
                                        .name().toLowerCase().replace('_', '-');
                            }

                            @Override
                            public void switchApproval(String approval)
                                    throws java.io.IOException {
                                dev.pironi.safety.ApprovalMode mode =
                                        dev.pironi.safety.ApprovalMode.parse(approval);
                                CliOptions updated = currentOptions.get()
                                        .withApprovalMode(mode);
                                lastSession.save(updated);
                                approvalPolicy.updateMode(mode);
                                currentOptions.set(updated);
                                agentContext.updateRuntimeSession(
                                        runtimeSessionDescription(updated)
                                );
                            }


                            @Override
                            public List<String> availableModels() {
                                var models = new java.util.LinkedHashSet<String>();
                                models.add(modelClient.model());
                                models.add("deepseek-v4-flash");
                                models.add("qwen3.6:35b-a3b");
                                models.add("gemma4:e4b");
                                return List.copyOf(models);
                            }

                            @Override
                            public List<InteractiveShell.ProviderChoice> availableProviders() {
                                List<InteractiveShell.ProviderChoice> providers = new java.util.ArrayList<>();
                                providers.add(new InteractiveShell.ProviderChoice("ollama", "Ollama"));
                                providers.add(new InteractiveShell.ProviderChoice("deepseek", "DeepSeek"));
                                providers.add(new InteractiveShell.ProviderChoice("openrouter", "OpenRouter"));
                                if (currentOptions.get().provider()
                                        == dev.pironi.model.ProviderType.OPENAI_COMPATIBLE) {
                                    providers.add(new InteractiveShell.ProviderChoice(
                                            "openai-compatible", "OpenAI-compatible"
                                    ));
                                }
                                return List.copyOf(providers);
                            }

                            @Override
                            public List<String> availableModels(String provider)
                                    throws java.io.IOException {
                                CliOptions current = currentOptions.get();
                                String seedModel = switch (provider) {
                                    case "ollama" -> current.provider()
                                            == dev.pironi.model.ProviderType.OLLAMA
                                            ? current.model() : "qwen3.6:35b-a3b";
                                    case "deepseek" -> current.provider()
                                            == dev.pironi.model.ProviderType.DEEPSEEK
                                            ? current.model() : "deepseek-v4-flash";
                                    case "openrouter" -> current.provider()
                                            == dev.pironi.model.ProviderType.OPENROUTER
                                            ? current.model() : "openrouter/auto";
                                    default -> current.model();
                                };
                                CliOptions catalogOptions = switchedOptions(
                                        current, provider, seedModel, System.getenv(),
                                        Path.of(System.getProperty("user.home"), ".hermes", ".env")
                                );
                                return modelCatalog.models(catalogOptions);
                            }
                        };
                InteractiveShell.ShellCommands shellC = new DefaultShellCommands(
                        sessions, compressor, skills, memory, capabilityReport, runtimeDoctor
                );
                InteractiveShell shell = new InteractiveShell(
                        terminal,
                        System.out,
                        loop::run,
                        modelCommands,
                        shellC,
                        status::idle
                );
                approvalPolicy.updateInteraction(shell.approvalInteraction(
                        status::outputStarted,
                        status::outputFinished
                ));
                int exitCode = shell.run(options.task());
                System.out.println("Trace: " + options.tracePath().toAbsolutePath().normalize());
                return exitCode;
            }

            AgentResult result = loop.run(options.task());
            System.out.println(result.output());
            System.out.println("Turns: " + result.turns());
            System.out.println("Trace: " + options.tracePath().toAbsolutePath().normalize());
            return result.success() ? 0 : 1;
        }
    }

    static CliOptions switchedOptions(
            CliOptions previous,
            String model,
            java.util.Map<String, String> environment,
            Path hermesEnvironmentFile
    ) {
        String provider = inferProvider(previous, model);
        return switchedOptions(previous, provider, model, environment, hermesEnvironmentFile);
    }

    static CliOptions switchedOptions(
            CliOptions previous,
            String provider,
            String model,
            java.util.Map<String, String> environment,
            Path hermesEnvironmentFile
    ) {
        dev.pironi.model.ProviderType target = dev.pironi.model.ProviderType.parse(provider);
        if (target == previous.provider()) return previous.withModel(model);

        if (target == dev.pironi.model.ProviderType.OLLAMA) {
            return previous.withProviderModel(
                    target, URI.create("http://127.0.0.1:11434"), model,
                    null, "OPENAI_API_KEY", 131_072
            );
        }

        String keyEnvironment = target == dev.pironi.model.ProviderType.DEEPSEEK
                ? "DEEPSEEK_API_KEY" : "OPENROUTER_API_KEY";
        if (target == dev.pironi.model.ProviderType.OPENAI_COMPATIBLE) {
            throw new IllegalArgumentException(
                    "OpenAI-compatible provider requires startup --base-url and --api-key-env"
            );
        }

        String key = ApiKeyResolver.resolve(
                environment,
                keyEnvironment,
                hermesEnvironmentFile
        );
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing API key in environment variable " + keyEnvironment
            );
        }
        return previous.withProviderModel(
                target,
                target == dev.pironi.model.ProviderType.DEEPSEEK
                        ? URI.create("https://api.deepseek.com")
                        : URI.create("https://openrouter.ai/api/v1"),
                model,
                key,
                keyEnvironment,
                target == dev.pironi.model.ProviderType.DEEPSEEK ? 1_000_000 : 200_000
        );
    }

    private static String inferProvider(CliOptions previous, String model) {
        if (model.startsWith("deepseek-") && !model.contains("/")) return "deepseek";
        if (model.contains("/")) return "openrouter";
        return previous.provider().name().toLowerCase().replace('_', '-');
    }

    static ToolRegistry configuredTools(List<Tool> availableTools, Set<String> deniedTools) {
        return configuredTools(availableTools, deniedTools, Set.of());
    }

    static ToolRegistry configuredTools(
            List<Tool> availableTools,
            Set<String> deniedTools,
            Set<String> allowedTools
    ) {
        Set<String> knownNames = availableTools.stream()
                .map(Tool::name)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> configuredNames = allowedTools.isEmpty() ? deniedTools : allowedTools;
        List<String> unknownNames = configuredNames.stream()
                .filter(name -> !knownNames.contains(name))
                .sorted()
                .toList();
        if (!unknownNames.isEmpty()) {
            String known = knownNames.stream()
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.joining(","));
            throw new IllegalArgumentException(
                    "Unknown tool name(s) in "
                            + (allowedTools.isEmpty() ? "--deny-tools: " : "--allow-tools: ")
                            + String.join(",", unknownNames)
                            + ". Known tools: " + known
            );
        }
        return new ToolRegistry(availableTools.stream()
                .filter(tool -> allowedTools.isEmpty()
                        ? !deniedTools.contains(tool.name())
                        : allowedTools.contains(tool.name()))
                .toList());
    }

    private static String runtimeSessionDescription(CliOptions options) {
        return """
                These values describe the running process. Do not inspect source or config files
                to rediscover them. If the user asks to change a setting that has a slash command,
                explain or use that command instead of inspecting the project.
                provider: %s
                model: %s
                workspace: %s
                approval: %s
                context: %d
                status: %s
                interactive: %s
                shell-scope: %s
                search-roots: %s
                """.formatted(
                options.provider().name().toLowerCase().replace('_', '-'),
                options.model(),
                options.workspace(),
                options.approvalMode().name().toLowerCase().replace('_', '-'),
                options.contextSize(),
                options.statusMode().name().toLowerCase(),
                options.interactive(),
                options.shellScope().name().toLowerCase(),
                options.searchRoots()
        );
    }

    private static void printUsage() {
        System.out.println("""
                Pironi - small Java 25 coding agent harness

                Required:
                  --model MODEL                                 default: last used; initially qwen3.6:35b-a3b

                Provider:
                  --provider ollama|deepseek|openrouter|openai-compatible
                  --base-url URL                                provider-specific default
                  --api-key-env NAME                            provider-specific default

                Agent:
                  --workspace PATH                              default: current directory
                  --approval ask|auto|read-only                 default: read-only
                  --activity auto                              allow tool activity without prompts;
                                                               overrides --approval
                  --interactive                                default
                  --no-interactive                             one-shot; requires --task
                  --task TEXT                                  optional initial interactive task
                  --max-turns N                                 default: 8
                  --context N                                   Ollama 8192, OpenRouter 200000, DeepSeek 1000000
                  --max-output-tokens N                         default: 4096
                  --timeout-seconds N                           model request timeout, default: 600
                  --trace PATH                                  default: WORKSPACE/.pironi/trace.jsonl
                  --pironi-home PATH                            default: ~/.pironi
                  --personal-context auto|allow|deny            auto: Ollama only
                  --status auto|always|never                    default: always
                  --verify-command COMMAND                     auto-detect Maven/Gradle
                  --deny-tools NAME,NAME                        remove named tools; unknown names fail startup
                  --allow-tools NAME,NAME                       enable only named tools; conflicts with --deny-tools
                  --shell-scope workspace|user|unrestricted    default: workspace lexical guardrail
                  --search-roots PATH,PATH                      allowed roots for find_files; default: workspace

                Examples:
                  java -jar pironi.jar

                  java -jar pironi.jar --model qwen3.6:35b-a3b

                  export DEEPSEEK_API_KEY=...
                  java -jar pironi.jar --provider deepseek

                  export OPENROUTER_API_KEY=...
                  java -jar pironi.jar --provider openrouter

                  java -jar pironi.jar --model qwen3.6:35b-a3b --no-interactive \\
                    --task "Inspect this project"

                  export DEEPSEEK_API_KEY=...
                  java -jar pironi.jar --provider deepseek --model deepseek-v4-flash \\
                    --task "Inspect this project" --workspace ./project
                """);
    }
}
