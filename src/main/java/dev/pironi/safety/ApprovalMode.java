package dev.pironi.safety;

public enum ApprovalMode {
    ASK,
    AUTO,
    READ_ONLY;

    public static ApprovalMode parse(String value) {
        return switch (value.toLowerCase()) {
            case "ask" -> ASK;
            case "auto" -> AUTO;
            case "read-only", "readonly" -> READ_ONLY;
            default -> throw new IllegalArgumentException(
                    "Unknown approval mode: " + value + " (expected ask, auto, or read-only)"
            );
        };
    }
}
