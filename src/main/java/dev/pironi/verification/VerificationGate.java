package dev.pironi.verification;

public interface VerificationGate {
    void markChanged();

    /** A change to these workspace paths; a gate that cares which files changed overrides this. */
    default void markChanged(java.util.List<String> paths) {
        markChanged();
    }

    boolean required();

    VerificationResult verifyIfRequired();
}
