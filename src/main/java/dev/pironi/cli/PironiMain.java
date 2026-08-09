package dev.pironi.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.agent.AgentLoop;
import dev.pironi.agent.AgentResult;
import dev.pironi.agent.AgentContext;
import dev.pironi.agent.ContextFileLoader;
import dev.pironi.agent.DecisionParser;
import dev.pironi.agent.CapabilityReport;
import dev.pironi.agent.FinalAnswerStreamer;
import dev.pironi.model.ProviderConfig;
import dev.pironi.model.SwitchableModelClient;
import dev.pironi.safety.ConsoleApprovalPolicy;
import dev.pironi.safety.ApprovalMode;
import dev.pironi.safety.ApprovalDecision;
import dev.pironi.safety.CheckpointManager;
import dev.pironi.safety.Workspace;
import dev.pironi.tool.ApplyPatchTool;
import dev.pironi.tool.AppControlTool;
import dev.pironi.tool.ListFilesTool;
import dev.pironi.tool.HttpGetTool;
import dev.pironi.tool.FindFilesTool;
import dev.pironi.tool.MoveFileTool;
import dev.pironi.tool.NetworkSpeedTool;
import dev.pironi.tool.ReadFileTool;
import dev.pironi.tool.ProposeSkillTool;
import dev.pironi.tool.RunCommandTool;
import dev.pironi.tool.RollbackCheckpointTool;
import dev.pironi.tool.SpawnSubagentTool;
import dev.pironi.tool.SubagentManager;
import dev.pironi.tool.SubagentResult;
import dev.pironi.tool.Tool;
import dev.pironi.tool.ToolRegistry;
import dev.pironi.tool.WriteFileTool;
import dev.pironi.tool.CsvTool;
import dev.pironi.tool.IcsCreateTool;
import dev.pironi.tool.OfficeOpenXmlTool;
import dev.pironi.tool.InspectFileTool;
import dev.pironi.tool.SystemInfoTool;
import dev.pironi.trace.JsonlTraceWriter;
import dev.pironi.status.NoOpStatusReporter;
import dev.pironi.status.StatusReporter;
import dev.pironi.status.StatusMode;
import dev.pironi.status.TerminalStatusReporter;
import dev.pironi.verification.ProjectVerificationGate;
import dev.pironi.verification.VerificationGate;
import dev.pironi.verification.NoOpVerificationGate;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class PironiMain {
    private PironiMain() {
    }

    /** Hard ceiling on a child sub-agent's turns, below the parent's default, so a runaway
     *  child cannot burn tokens indefinitely. Enforced together with the manager's deadline. */
    private static final int MAX_SUBAGENT_TURNS = 6;

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

        Set<Path> hiddenAgentPaths = Set.of(options.tracePath().toAbsolutePath().normalize());
        List<Tool> availableTools = new ArrayList<>(List.of(
                new ListFilesTool(workspace, 500, options.searchRoots(), hiddenAgentPaths),
                new ReadFileTool(
                        workspace, 32_000, options.searchRoots(), hiddenAgentPaths
                ),
                new InspectFileTool(workspace, options.searchRoots()),
                new SystemInfoTool(workspace),
                new AppControlTool(),
                new dev.pironi.tool.ProcessInspectTool(),
                new dev.pironi.tool.ProcessControlTool(),
                new ProposeSkillTool(memory),
                new WriteFileTool(workspace),
                new ApplyPatchTool(workspace, checkpoints),
                new MoveFileTool(workspace, checkpoints),
                new CsvTool(workspace, CsvTool.Operation.MERGE),
                new CsvTool(workspace, CsvTool.Operation.SANITIZE),
                new IcsCreateTool(workspace),
                new OfficeOpenXmlTool(workspace, OfficeOpenXmlTool.Format.XLSX),
                new OfficeOpenXmlTool(workspace, OfficeOpenXmlTool.Format.DOCX),
                new OfficeOpenXmlTool(workspace, OfficeOpenXmlTool.Format.PPTX),
                new RollbackCheckpointTool(checkpoints),
                new FindFilesTool(options.searchRoots(), hiddenAgentPaths),
                new HttpGetTool(),
                new NetworkSpeedTool(),
                new RunCommandTool(
                        workspace, Duration.ofSeconds(90), 32_000, options.shellScope()
                )
        ));

        // Cloud-only sub-agent support. Spawning a second local model instance would contend
        // for CPU/RAM, so spawn_subagent is registered only for cloud providers (never Ollama).
        dev.pironi.model.ProviderType providerType = options.provider();
        SubagentManager subagentManager = null;
        if (providerType != dev.pironi.model.ProviderType.OLLAMA) {
            List<Tool> readOnlyTools = List.of(
                    new HttpGetTool(),
                    new ReadFileTool(workspace, 32_000, options.searchRoots(), hiddenAgentPaths),
                    new ListFilesTool(workspace, 500, options.searchRoots(), hiddenAgentPaths),
                    new FindFilesTool(options.searchRoots(), hiddenAgentPaths)
            );
            // Deliberately NO spawn_subagent here: the child is read-only and cannot spawn a
            // grandchild, so nested delegation (parent waits child waits grandchild) cannot
            // deadlock. A child only gathers data; the parent owns all further delegation.
            ToolRegistry childRegistry = new ToolRegistry(readOnlyTools);
            dev.pironi.safety.ApprovalPolicy childPolicy = (ignoredTool, ignoredArgs) -> ApprovalDecision.ALLOW;
            subagentManager = new SubagentManager(
                    options.maxSubagents(),
                    Duration.ofSeconds(options.subagentTimeoutSeconds()),
                    subtask -> runChildSubagent(
                            modelClient, objectMapper, childRegistry, childPolicy,
                            options, subtask
                    )
            );
            availableTools.add(new SpawnSubagentTool(subagentManager));
        } else {
            System.out.println("Capability note: sub-agent spawning is disabled with a local "
                    + "provider (ollama) to avoid running a second model instance on this machine.");
        }
        SubagentManager finalSubagentManager = subagentManager;
        Set<String> effectiveDeniedTools = autoSafeDeniedTools(options);
        ToolRegistry configuredRegistry = configuredTools(
                availableTools, effectiveDeniedTools, options.allowTools()
        );
        ToolRegistry tools = toolsForApproval(configuredRegistry, options.approvalMode());

        boolean statusEnabled = statusEnabled(
                options.statusMode(),
                System.console() != null,
                System.getProperty("os.name", "")
        );
        boolean interactive = options.interactive() && !options.noTui();
        ThemeStore themeStore = new ThemeStore(options.pironiHome());
        dev.pironi.status.ThemeSettings theme = themeStore.load();

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
                        terminal,
                        theme
                );
            } else {
                status = new TerminalStatusReporter(
                        options.model(),
                        options.workspace(),
                        options.contextSize(),
                        options.maxTurns(),
                        System.out,
                        theme
                );
            }
        } else {
            status = new NoOpStatusReporter();
        }

        if (status instanceof TerminalStatusReporter statusTerminal && finalSubagentManager != null) {
            statusTerminal.setSubagentCounts(() -> new int[]{
                    finalSubagentManager.activeCount(),
                    finalSubagentManager.maxConcurrent()
            });
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
            java.util.Map<String, String> disabledReasons = new java.util.HashMap<>();
            for (Tool tool : availableTools) {
                if (tools.find(tool.name()).isPresent()) continue;
                String reason;
                if (options.approvalMode() == ApprovalMode.READ_ONLY && tool.mutating()) {
                    reason = "disabled by approval=read-only";
                } else if (tool.name().equals("run_command")
                        && options.approvalMode() == ApprovalMode.AUTO
                        && options.shellScope() == dev.pironi.tool.ShellScope.WORKSPACE
                        && options.allowTools().isEmpty()
                        && !options.denyTools().contains("run_command")) {
                    reason = "blocked by auto-safe workspace policy; use --shell-scope user "
                            + "or explicitly allow run_command";
                } else if (!options.allowTools().isEmpty()) {
                    reason = "not included by --allow-tools";
                } else {
                    reason = "disabled by --deny-tools";
                }
                disabledReasons.put(tool.name(), reason);
            }
            CapabilityReport capabilityReport = new CapabilityReport(
                    tools, agentContext,
                    availableTools.stream().map(Tool::name).toList(), disabledReasons
            );
            if (interactive && disabledReasons.containsKey("run_command")) {
                System.out.println("Capability note: host shell "
                        + dev.pironi.tool.PlatformShell.name()
                        + " is available, but run_command is "
                        + disabledReasons.get("run_command") + ".");
            }
            RuntimeDoctor runtimeDoctor = new RuntimeDoctor(
                    options.workspace(), options.pironiHome(), capabilityReport
            );
            ConsoleApprovalPolicy approvalPolicy = new ConsoleApprovalPolicy(
                    options.approvalMode(),
                    input,
                    System.out,
                    interactive
            );
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
                    interactive ? new FinalAnswerStreamer(System.out, terminal, theme) : null,
                    memory,
                    capabilityReport,
                    finalSubagentManager == null
                            ? null
                            : finalSubagentManager,
                    Duration.ofSeconds(
                            finalSubagentManager == null ? 120 : options.subagentTimeoutSeconds()
                    )
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
                                tools.setReadOnly(mode == ApprovalMode.READ_ONLY);
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
                        status::idle,
                        theme,
                        themeStore
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

    /**
     * Runs a sub-task in a dedicated read-only AgentLoop inside a virtual thread. The child
     * can only use http_get/read_file/list_files/find_files, never mutate state, so no
     * approval prompt is required and the user is never interrupted.
     */
    private static SubagentResult runChildSubagent(
            dev.pironi.model.ModelClient modelClient,
            ObjectMapper objectMapper,
            ToolRegistry childRegistry,
            dev.pironi.safety.ApprovalPolicy childPolicy,
            CliOptions options,
            String subtask
    ) {
        AgentContext childContext = new AgentContext("", "", "");
        dev.pironi.trace.CollectingTraceWriter childTrace = new dev.pironi.trace.CollectingTraceWriter();
        // Cap the child's turns below the parent's default so a runaway child cannot
        // burn tokens indefinitely; the manager's deadline interrupts it as a hard limit.
        int childTurns = Math.min(options.maxTurns(), MAX_SUBAGENT_TURNS);
        AgentLoop childLoop = new AgentLoop(
                modelClient,
                new DecisionParser(objectMapper),
                objectMapper,
                childRegistry,
                childPolicy,
                childTrace,
                childContext,
                new dev.pironi.status.NoOpStatusReporter(),
                new NoOpVerificationGate(),
                childTurns,
                4
        );
        try {
            AgentResult result = childLoop.run(subtask);
            SubagentResult base = result.success()
                    ? SubagentResult.completed(
                            "child",
                            "subagent",
                            "finalAnswer: " + result.output()
                    )
                    : SubagentResult.error("child", "subagent", "no finalAnswer: " + result.output());
            return new SubagentResult(base.id(), base.name(), base.status(), base.output(),
                    childTrace.lines());
        } catch (Exception e) {
            return new SubagentResult(
                    "child", "subagent", "error",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    childTrace.lines()
            );
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

    static Set<String> autoSafeDeniedTools(CliOptions options) {
        if (options.approvalMode() != ApprovalMode.AUTO
                || options.shellScope() != dev.pironi.tool.ShellScope.WORKSPACE
                || !options.allowTools().isEmpty()) {
            return options.denyTools();
        }
        Set<String> denied = new HashSet<>(options.denyTools());
        denied.add("run_command");
        return Set.copyOf(denied);
    }

    static boolean statusEnabled(StatusMode mode, boolean consolePresent, String osName) {
        boolean windows = osName.toLowerCase(java.util.Locale.ROOT).contains("win");
        return mode == StatusMode.ALWAYS
                || (mode == StatusMode.AUTO && consolePresent && !windows);
    }

    static ToolRegistry toolsForApproval(ToolRegistry configured, ApprovalMode mode) {
        configured.setReadOnly(mode == ApprovalMode.READ_ONLY);
        return configured;
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
                  --activity auto                              allow scoped tool activity without prompts;
                                                               overrides --approval; default workspace shell is disabled
                  --interactive                                default
                  --no-interactive                             one-shot; requires --task or --task-file
                  --task TEXT                                  optional initial interactive task
                  --task-file PATH                             read the task as UTF-8; conflicts with --task
                  --max-turns N                                 default: 8
                  --context N                                   Ollama 8192, OpenRouter 200000, DeepSeek 1000000
                  --max-output-tokens N                         default: 4096
                  --timeout-seconds N                           model request timeout, default: 600
                  --trace PATH                                  default: WORKSPACE/.pironi/trace.jsonl
                  --pironi-home PATH                            default: ~/.pironi
                  --personal-context auto|allow|deny            auto: Ollama only
                  --status auto|always|never                    default: auto
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
