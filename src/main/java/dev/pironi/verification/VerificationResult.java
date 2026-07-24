package dev.pironi.verification;

public record VerificationResult(
        boolean attempted,
        boolean success,
        String command,
        String output
) {
    public static VerificationResult notRequired() {
        return new VerificationResult(false, true, "", "");
    }
}
