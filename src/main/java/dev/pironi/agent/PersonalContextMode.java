package dev.pironi.agent;

public enum PersonalContextMode {
    AUTO,
    ALLOW,
    DENY;

    public static PersonalContextMode parse(String value) {
        return switch (value.toLowerCase()) {
            case "auto" -> AUTO;
            case "allow" -> ALLOW;
            case "deny" -> DENY;
            default -> throw new IllegalArgumentException(
                    "Unknown personal context mode: " + value + " (expected auto, allow, or deny)"
            );
        };
    }
}
