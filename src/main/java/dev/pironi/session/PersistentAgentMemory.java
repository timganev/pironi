package dev.pironi.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.pironi.agent.AgentMemory;
import dev.pironi.model.ChatMessage;
import dev.pironi.model.ModelResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Coordinates the filesystem stores without coupling AgentLoop to their formats. */
public final class PersistentAgentMemory implements AgentMemory {
    private final SessionStore sessions;
    private final ContextCompressor compressor;
    private final SkillStore skills;
    private final ObjectMapper mapper;
    private final String model;
    private final Path workspace;
    private final int contextLimit;
    private final int maxTurns;
    private List<ChatMessage> pendingResume = List.of();
    private String activeSkill = "";
    private String activeSkillContent = "";
    private String lastTask = "";
    private String lastAnswer = "";
    private boolean compressionRequested;

    public PersistentAgentMemory(SessionStore sessions, ContextCompressor compressor,
            SkillStore skills, ObjectMapper mapper, String model, Path workspace,
            int contextLimit, int maxTurns) {
        this.sessions = sessions;
        this.compressor = compressor;
        this.skills = skills;
        this.mapper = mapper;
        this.model = model;
        this.workspace = workspace;
        this.contextLimit = contextLimit;
        this.maxTurns = maxTurns;
    }

    @Override public synchronized List<ChatMessage> begin(String task) {
        if (sessions.currentMeta() == null) {
            sessions.startSession(model, workspace, contextLimit, maxTurns);
            sessions.saveMeta();
        }
        List<ChatMessage> result = pendingResume;
        pendingResume = List.of();
        return result;
    }

    @Override public synchronized void record(ChatMessage message, long prompt, long output) {
        sessions.appendTurn(message, prompt, output);
        sessions.saveMeta();
    }

    @Override public synchronized void recordTool(String name, JsonNode args, String output) {
        sessions.appendToolResult(name, args, output);
    }

    @Override public synchronized void addTokens(ModelResponse response) {
        compressor.addTokens(response.promptTokens(), response.outputTokens());
    }

    @Override public synchronized boolean shouldCompress() {
        boolean result = compressionRequested || compressor.shouldCompress();
        compressionRequested = false;
        return result;
    }

    @Override public synchronized String compressionPrompt(List<ChatMessage> messages, String task) {
        return compressor.buildCompressionPrompt(messages, task);
    }

    @Override public synchronized String storeSummary(String summary) {
        return compressor.storeSummary(summary);
    }

    @Override public synchronized void checkpoint(List<ChatMessage> messages, String task) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", 1);
        root.put("task", task);
        root.put("summary", compressor.lastSummary());
        root.put("activeSkill", activeSkill);
        var array = root.putArray("messages");
        for (ChatMessage message : messages) {
            array.addObject().put("role", message.role()).put("content", message.content());
        }
        sessions.saveCheckpoint(root.toString());
        sessions.saveMeta();
    }

    @Override public synchronized String promptContext() {
        StringBuilder result = new StringBuilder();
        String index = skills.loadIndex();
        if (!index.isBlank()) result.append("Available skill catalog:\n").append(index);
        if (!activeSkillContent.isBlank()) {
            if (!result.isEmpty()) result.append("\n\n");
            result.append("Active skill '").append(activeSkill).append("':\n")
                    .append(activeSkillContent);
        }
        return result.toString();
    }

    @Override public synchronized void completed(String task, String answer) {
        lastTask = task;
        lastAnswer = answer;
    }

    @Override public synchronized void finished(boolean success) {
        sessions.updateStatus(success ? "completed" : "failed");
    }

    public synchronized String resume(String id) {
        var checkpoint = sessions.loadCheckpoint(id == null || id.isBlank()
                ? sessions.latestSessionId().orElse("") : id);
        if (checkpoint.isEmpty()) return "No checkpoint found";
        try {
            JsonNode root = mapper.readTree(checkpoint.get());
            if (root.path("version").asInt() != 1 || !root.path("messages").isArray()) {
                return "Unsupported checkpoint format";
            }
            List<ChatMessage> restored = new ArrayList<>();
            for (JsonNode item : root.path("messages")) {
                String role = item.path("role").asText();
                String content = item.path("content").asText();
                switch (role) {
                    case "system" -> restored.add(ChatMessage.system(content));
                    case "assistant" -> restored.add(ChatMessage.assistant(content));
                    case "user" -> restored.add(ChatMessage.user(content));
                    default -> { return "Invalid checkpoint message role: " + role; }
                }
            }
            pendingResume = List.copyOf(restored);
            String skill = root.path("activeSkill").asText("");
            if (!skill.isBlank()) activateSkill(skill);
            return "Session scheduled for resume: " + restored.size() + " messages";
        } catch (Exception e) {
            return "Invalid checkpoint: " + e.getMessage();
        }
    }

    public synchronized void requestCompression() { compressionRequested = true; }

    public synchronized String startNewSession() {
        if (sessions.currentMeta() != null) sessions.updateStatus("closed");
        var session = sessions.startSession(model, workspace, contextLimit, maxTurns);
        sessions.saveMeta();
        compressor.reset();
        pendingResume = List.of();
        activeSkill = "";
        activeSkillContent = "";
        lastTask = "";
        lastAnswer = "";
        compressionRequested = false;
        return "New session started: " + session.id();
    }

    public synchronized String activateSkill(String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("off")) {
            activeSkill = "";
            activeSkillContent = "";
            return "Active skill cleared.";
        }
        var content = skills.load(name);
        if (content.isEmpty()) return "Skill not found: " + name;
        activeSkill = name;
        activeSkillContent = content.get();
        return "Skill activated: " + name + " (" + activeSkillContent.length() + " chars)";
    }

    public synchronized String saveLastTurnAsSkill(String title) {
        if (lastTask.isBlank() || lastAnswer.isBlank()) return "No completed turn to save.";
        String name = title == null || title.isBlank() ? "last-turn" : title;
        String content = "---\ndescription: \"Learned from a completed Pironi turn\"\n---\n\n"
                + "# " + name + "\n\n## User goal\n" + lastTask
                + "\n\n## Successful result\n" + lastAnswer + "\n";
        return skills.save(name, content) ? "Skill saved: " + name : "Could not save skill: " + name;
    }
}
