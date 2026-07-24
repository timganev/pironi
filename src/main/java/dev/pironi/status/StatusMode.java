package dev.pironi.status;

public enum StatusMode {
    AUTO,
    ALWAYS,
    NEVER;

    public static StatusMode parse(String value) {
        return switch (value.toLowerCase()) {
            case "auto" -> AUTO;
            case "always" -> ALWAYS;
            case "never" -> NEVER;
            default -> throw new IllegalArgumentException(
                    "Unknown status mode: " + value + " (expected auto, always, or never)"
            );
        };
    }
}
