package dev.pironi.agent;

public final class AgentContext {
    private final String soul;
    private final String userProfile;
    private final String projectInstructions;
    private final String personalSources;
    private volatile String runtimeSession = "";

    public AgentContext(String soul, String userProfile, String projectInstructions) {
        this(soul, userProfile, projectInstructions, "");
    }

    public AgentContext(String soul, String userProfile, String projectInstructions,
            String personalSources) {
        this.soul = normalize(soul);
        this.userProfile = normalize(userProfile);
        this.projectInstructions = normalize(projectInstructions);
        this.personalSources = normalize(personalSources);
    }

    /** The files the identity and user profile were read from, one per line. */
    public String personalSources() {
        return personalSources;
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
