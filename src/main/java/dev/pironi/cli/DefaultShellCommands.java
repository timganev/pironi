package dev.pironi.cli;

import dev.pironi.session.ContextCompressor;
import dev.pironi.session.SessionStore;
import dev.pironi.session.SkillStore;
import dev.pironi.session.PersistentAgentMemory;
import dev.pironi.agent.CapabilityReport;

import java.io.IOException;

/**
 * Default implementation of InteractiveShell.ShellCommands backed by stores.
 */
final class DefaultShellCommands implements InteractiveShell.ShellCommands {
    private final SessionStore sessions;
    private final ContextCompressor compressor;
    private final SkillStore skills;
    private final PersistentAgentMemory memory;
    private final CapabilityReport capabilities;
    private final RuntimeDoctor doctor;
    private dev.pironi.tool.ToolRegistry registry;
    private dev.pironi.session.UserFacts userFacts;
    private dev.pironi.safety.Workspace workspace;
    private java.util.function.Consumer<java.nio.file.Path> workspaceChanged = path -> { };
    private boolean personalContextLoaded;
    private Runnable accessChanged = () -> { };

    /** Wired after construction because the registry is assembled later in startup. */
    void useRegistry(dev.pironi.tool.ToolRegistry toolRegistry) {
        this.registry = toolRegistry;
    }

    /**
     * Refreshes the runtime description the model sees. Without this a granted directory is
     * usable but invisible: the model reads the old search-roots line, concludes it has no
     * access and refuses without ever calling the tool.
     */
    void onAccessChanged(Runnable callback) {
        if (callback != null) this.accessChanged = callback;
    }

    /**
     * The callback carries the switch to everything that keeps its own copy of the workspace:
     * the saved session, the runtime description the model reads, and the read grants.
     */
    void useWorkspace(dev.pironi.safety.Workspace sandbox,
            java.util.function.Consumer<java.nio.file.Path> onChange) {
        this.workspace = sandbox;
        if (onChange != null) this.workspaceChanged = onChange;
    }

    /**
     * The one command that takes a directory: reading and writing together. Splitting them
     * meant two commands for one intent - and a granted directory that was readable and still
     * untouchable, which read to the agent as a file no tool could ever change.
     */
    @Override public String workspace(String argument) {
        if (workspace == null) return "Workspace switching not available.";
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty()) {
            StringBuilder out = new StringBuilder("Workspace (reading and writing act here): ")
                    .append(workspace.root());
            java.util.List<java.nio.file.Path> readable = registry == null
                    ? java.util.List.of()
                    : registry.grants().grantedRoots().stream()
                            .filter(root -> !root.equals(workspace.root())).sorted().toList();
            if (!readable.isEmpty()) {
                out.append("\nStill readable from earlier in this session: ").append(readable);
            }
            return out.append("\nChange with: /workspace PATH").toString();
        }
        java.nio.file.Path previous = workspace.root();
        try {
            java.nio.file.Path moved = workspace.switchTo(expandHome(trimmed));
            if (moved.equals(previous)) return "Already the workspace: " + moved;
            if (registry != null) {
                // Record the directory being left as an explicit read grant. It stays readable
                // either way - the read tools keep the roots they started with - but only a
                // recorded grant appears in /workspace and in what the model is told.
                try {
                    registry.grants().grantRoot(previous);
                } catch (java.io.IOException ignored) {
                    // It was the workspace a moment ago; if it has just become unreadable,
                    // saying so here would only distract from the switch that did work.
                }
            }
            workspaceChanged.accept(moved);
            return "Workspace switched for this session: " + moved
                    + "\n  Reading, writing and run_command now act there;"
                    + " " + previous + " stays readable."
                    + "\n  Checkpoints taken before the switch still roll back to where"
                    + " those files are. The trace stays where the session started.";
        } catch (java.io.IOException | RuntimeException e) {
            return "Could not switch workspace: " + e.getMessage();
        }
    }

    private static java.nio.file.Path expandHome(String path) {
        String home = System.getProperty("user.home", "");
        if (path.equals("~")) return java.nio.file.Path.of(home);
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return java.nio.file.Path.of(home, path.substring(2));
        }
        return java.nio.file.Path.of(path);
    }

    void useUserFacts(dev.pironi.session.UserFacts facts, boolean loaded) {
        this.userFacts = facts;
        this.personalContextLoaded = loaded;
    }

    @Override public String remember(String argument) {
        if (userFacts == null) return "Memory not available.";
        String trimmed = argument == null ? "" : argument.trim();
        try {
            if (trimmed.isEmpty()) {
                var facts = userFacts.list();
                if (facts.isEmpty()) return "Nothing remembered yet. Use /remember <preference>.";
                StringBuilder out = new StringBuilder("Remembered:");
                for (int i = 0; i < facts.size(); i++) {
                    out.append("\n  ").append(i + 1).append(". ").append(facts.get(i));
                }
                return out + "\nRemove one with /remember forget N.";
            }
            if (trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("forget")) {
                String rest = trimmed.substring("forget".length()).trim();
                int index;
                try {
                    index = Integer.parseInt(rest);
                } catch (NumberFormatException e) {
                    return "Usage: /remember forget N (see /remember for the numbers)";
                }
                String removed = userFacts.removeAt(index);
                return removed.isEmpty() ? "No such entry: " + index : "Forgotten: " + removed;
            }
            String stored = userFacts.add(trimmed);
            if (stored.isEmpty()) {
                return "Not stored: the text is empty, too long, or already remembered.";
            }
            // Saying this now avoids the puzzle of a preference that is written down but
            // never acted on, which is what happens when USER.md is not loaded.
            String note = personalContextLoaded ? ""
                    : " Note: USER.md is not being loaded in this session, so this takes effect"
                    + " only with --personal-context allow.";
            return "Remembered: " + stored + note;
        } catch (IOException e) {
            return "Could not update USER.md: " + e.getMessage();
        }
    }

    /**
     * One command with sub-verbs rather than four separate slash commands: the slash menu is
     * rendered inline and a handful of extra entries pushes the prompt off a short terminal,
     * which silently breaks the keyboard tests.
     */
    /**
     * Findings outlive the run that learned them, which is the point and also the risk: a file
     * that has since been deleted, a layout that has since changed. Being able to see them and
     * drop them is what keeps that trade honest.
     */
    @Override public String findings(String argument) {
        if (memory == null) return "Findings not available.";
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.equalsIgnoreCase("clear")) {
            return memory.forgetFindings()
                    ? "Cleared what earlier runs established for this workspace."
                    : "Nothing was stored for this workspace.";
        }
        if (!trimmed.isEmpty()) return "Usage: /findings [clear]";
        var stored = memory.storedFindings();
        if (stored.isEmpty()) return "Nothing established by earlier runs here.";
        StringBuilder out = new StringBuilder("Established by earlier runs here:");
        for (var finding : stored) {
            // Date and origin are for you, not for the model: /resume on that session id shows
            // the conversation a claim came from, which is what you need when one turns out wrong.
            out.append("\n  ").append(finding.date().isEmpty() ? "(undated)" : finding.date())
                    .append("  ").append(finding.session().isEmpty() ? "-" : finding.session())
                    .append("\n    ").append(finding.text());
        }
        return out.append("\nDrop them with /findings clear; /resume ID reopens a session.")
                .toString();
    }

    @Override public String access(String argument) {
        if (registry == null) return "Access control not available.";
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty()) return showAccess();
        int space = trimmed.indexOf(' ');
        String verb = space < 0 ? trimmed : trimmed.substring(0, space);
        String rest = space < 0 ? "" : trimmed.substring(space + 1).trim();
        return switch (verb) {
            case "allow-tool" -> allowTool(rest);
            case "deny-tool" -> denyTool(rest);
            // Directories used to be granted here as well, in three variants. Taking a
            // directory is one intent, and /workspace is the one command for it now.
            case "allow-dir", "deny-dir", "remember-dir", "forget-dir" ->
                    "Directories are not granted here any more. Take one with /workspace PATH.";
            default -> "Usage: /access [allow-tool NAME | deny-tool NAME]";
        };
    }

    private String showAccess() {
        var grants = registry.grants();
        return "Blocked tools: " + (grants.disabledTools().isEmpty()
                ? "(none)" : grants.disabledTools().stream().sorted().toList())
                + "\nChange with: /access allow-tool NAME | deny-tool NAME"
                + "\nDirectories: see /workspace";
    }

    private String allowTool(String name) {
        if (registry == null) return "Access control not available.";
        if (name == null || name.isBlank()) return "Usage: /access allow-tool NAME";
        String tool = name.trim();
        if (registry.allImplemented().stream().noneMatch(t -> t.name().equals(tool))) {
            return "No such tool in this build: " + tool;
        }
        boolean enabled = registry.grants().enableTool(tool);
        accessChanged.run();
        return enabled
                ? "Tool enabled for this session: " + tool
                : "Tool was not blocked: " + tool;
    }

    private String denyTool(String name) {
        if (registry == null) return "Access control not available.";
        if (name == null || name.isBlank()) return "Usage: /access deny-tool NAME";
        String tool = name.trim();
        if (registry.allImplemented().stream().noneMatch(t -> t.name().equals(tool))) {
            return "No such tool in this build: " + tool;
        }
        registry.grants().disableTool(tool);
        accessChanged.run();
        return "Tool blocked for this session: " + tool;
    }

    DefaultShellCommands(SessionStore sessions, ContextCompressor compressor, SkillStore skills,
            PersistentAgentMemory memory, CapabilityReport capabilities, RuntimeDoctor doctor) {
        this.sessions = sessions;
        this.compressor = compressor;
        this.skills = skills;
        this.memory = memory;
        this.capabilities = capabilities;
        this.doctor = doctor;
    }

    @Override public String listSessions() {
        try {
            var list = sessions.listSessions();
            if (list.isEmpty()) return "No saved sessions.";
            StringBuilder result = new StringBuilder("Sessions:");
            for (var session : list) {
                result.append(System.lineSeparator()).append(
                        "  %s  %s  %s  %d tokens".formatted(
                                session.id(),
                                session.model(),
                                session.created().substring(0, 16),
                                session.totalTokens()
                        )
                );
            }
            return result.toString();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    @Override public String newSession() {
        return memory.startNewSession();
    }

    @Override public String capabilities() {
        return capabilities.render();
    }

    @Override public String doctor() {
        return doctor.run();
    }

    @Override public String resumeSession(String id) {
        return memory.resume(id);
    }

    @Override public String deleteSession(String id) {
        return sessions.deleteSession(id) ? "Session deleted." : "Not found.";
    }

    @Override public String searchSessions(String query) {
        try {
            var found = sessions.searchSessions(query);
            return found.isEmpty() ? "No matches." : String.join("\n", found);
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    @Override public String compressStatus() {
        return String.format(
                "compression: %s  pending: %s  threshold: %.0f%%  used: %.0f/%d tokens (%.0f%%)",
                compressor.enabled() ? "on" : "off",
                memory.compressionPending() ? "yes" : "no",
                compressor.threshold() * 100,
                (double)compressor.usedTokens(), compressor.contextLimit(),
                compressor.usagePercent());
    }

    @Override public String setCompression(String arg) {
        String message = null;
        switch (arg) {
            case "off" -> compressor.setEnabled(false);
            case "on" -> compressor.setEnabled(true);
            case "now" -> {
                memory.requestCompression();
                message = "Compression scheduled; if the next request has no older eligible "
                        + "context, it remains pending.";
            }
            default -> {
                try { compressor.setThreshold(Double.parseDouble(arg)); }
                catch (NumberFormatException e) {
                    return "Usage: /compress on|off|now|0.0-1.0";
                }
            }
        }
        return message == null ? compressStatus() : message + "\n" + compressStatus();
    }

    @Override public String listSkills() {
        try {
            var list = skills.list();
            if (list.isEmpty()) return "No skills installed.";
            StringBuilder result = new StringBuilder("Skills:");
            for (var skill : list) {
                result.append(System.lineSeparator())
                        .append("  ")
                        .append(skill.name())
                        .append(" — ")
                        .append(skill.description());
            }
            return result.toString();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    @Override public String loadSkill(String name) {
        return memory.activateSkill(name);
    }

    @Override public String saveSkill(String title) {
        return memory.saveLastTurnAsSkill(title);
    }

    @Override public String pendingSkill() { return memory.pendingSkill(); }

    @Override public String acceptSkill(String mode) {
        if (!mode.isBlank() && !mode.equalsIgnoreCase("replace")) {
            return "Usage: /accept-skill [replace]";
        }
        return memory.acceptPendingSkill(mode.equalsIgnoreCase("replace"));
    }

    @Override public String rejectSkill() { return memory.rejectPendingSkill(); }

    @Override public String forgetSkill(String name) {
        return skills.archive(name) ? "Skill archived: " + name : "Not found: " + name;
    }

    @Override public String pruneSkills() {
        try {
            int n = skills.pruneStale(90);
            return "Pruned " + n + " stale skills.";
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }
}
