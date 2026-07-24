package dev.pironi.cli;

import dev.pironi.session.ContextCompressor;
import dev.pironi.session.SessionStore;
import dev.pironi.session.SkillStore;

import java.io.IOException;

/**
 * Default implementation of InteractiveShell.ShellCommands backed by stores.
 */
final class DefaultShellCommands implements InteractiveShell.ShellCommands {
    private final SessionStore sessions;
    private final ContextCompressor compressor;
    private final SkillStore skills;

    DefaultShellCommands(SessionStore sessions, ContextCompressor compressor, SkillStore skills) {
        this.sessions = sessions;
        this.compressor = compressor;
        this.skills = skills;
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

    @Override public String resumeSession(String id) {
        var msgs = sessions.loadCheckpoint(id.isEmpty() ? null : id);
        return msgs.isPresent()
                ? "Checkpoint loaded: " + msgs.get().length() + " chars"
                : "No checkpoint found";
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
        return String.format("compression: %s  threshold: %.0f%%  used: %.0f/%d tokens (%.0f%%)",
                compressor.enabled() ? "on" : "off",
                compressor.threshold() * 100,
                (double)compressor.usedTokens(), compressor.contextLimit(),
                compressor.usagePercent());
    }

    @Override public String setCompression(String arg) {
        String message = null;
        switch (arg) {
            case "off" -> compressor.setEnabled(false);
            case "on" -> compressor.setEnabled(true);
            case "now" -> message = "Summary:\n" + compressor.lastSummary();
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
        var content = skills.load(name);
        return content.isPresent()
                ? "Skill loaded: " + name + " (" + content.get().length() + " chars)"
                : "Skill not found: " + name;
    }

    @Override public String saveSkill(String title) {
        return "Auto-save from last turn. Not yet implemented.";
    }

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
