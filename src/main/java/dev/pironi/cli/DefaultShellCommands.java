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
     * One command with sub-verbs rather than four separate slash commands: the slash menu is
     * rendered inline and a handful of extra entries pushes the prompt off a short terminal,
     * which silently breaks the keyboard tests.
     */
    @Override public String access(String argument) {
        if (registry == null) return "Access control not available.";
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty()) return showAccess();
        int space = trimmed.indexOf(' ');
        String verb = space < 0 ? trimmed : trimmed.substring(0, space);
        String rest = space < 0 ? "" : trimmed.substring(space + 1).trim();
        return switch (verb) {
            case "allow-dir" -> allowDirectory(rest);
            case "deny-dir" -> denyDirectory(rest);
            case "allow-tool" -> allowTool(rest);
            case "deny-tool" -> denyTool(rest);
            default -> "Usage: /access [allow-dir PATH | deny-dir PATH "
                    + "| allow-tool NAME | deny-tool NAME]";
        };
    }

    private String showAccess() {
        var grants = registry.grants();
        StringBuilder out = new StringBuilder("Granted this session:");
        out.append("\n  directories: ").append(grants.grantedRoots().isEmpty()
                ? "(none beyond startup --search-roots)" : grants.grantedRoots());
        out.append("\n  blocked tools: ").append(grants.disabledTools().isEmpty()
                ? "(none)" : grants.disabledTools().stream().sorted().toList());
        out.append("\nChange with: /access allow-dir PATH | deny-dir PATH "
                + "| allow-tool NAME | deny-tool NAME");
        return out.toString();
    }

    private String allowDirectory(String path) {
        if (registry == null) return "Access control not available.";
        if (path == null || path.isBlank()) return "Usage: /access allow-dir PATH";
        try {
            java.nio.file.Path granted = registry.grants().grantRoot(java.nio.file.Path.of(path.trim()));
            accessChanged.run();
            return "Read access granted for this session: " + granted;
        } catch (java.io.IOException | RuntimeException e) {
            return "Could not grant access: " + e.getMessage();
        }
    }

    private String denyDirectory(String path) {
        if (registry == null) return "Access control not available.";
        if (path == null || path.isBlank()) return "Usage: /access deny-dir PATH";
        boolean removed = registry.grants().revokeRoot(java.nio.file.Path.of(path.trim()));
        accessChanged.run();
        return removed ? "Access revoked: " + path.trim()
                : "Not granted in this session: " + path.trim();
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
