package dev.pironi.agent;

public final class AgentContext {
    private final String soul;
    private final String userProfile;
    private final String projectInstructions;
    private volatile String runtimeSession = "";

    public AgentContext(String soul, String userProfile, String projectInstructions) {
        this.soul = normalize(soul);
        this.userProfile = normalize(userProfile);
        this.projectInstructions = normalize(projectInstructions);
    }

    public String soul() {
        return soul;
    }

    public String userProfile() {
        return userProfile;
    }

    public String projectInstructions() {
        return projectInstructions;
    }

    public String runtimeSession() {
        return runtimeSession;
    }

    public void updateRuntimeSession(String runtimeSession) {
        this.runtimeSession = normalize(runtimeSession);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
