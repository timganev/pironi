package dev.pironi.tool;

public enum ShellScope {
    WORKSPACE,
    USER,
    UNRESTRICTED;

    public static ShellScope parse(String value) {
        return switch (value.toLowerCase()) {
            case "workspace" -> WORKSPACE;
            case "user" -> USER;
            case "unrestricted" -> UNRESTRICTED;
            default -> throw new IllegalArgumentException(
                    "Unknown shell scope: " + value
                            + " (expected workspace, user, or unrestricted)"
            );
        };
    }
}
