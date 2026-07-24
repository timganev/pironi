package dev.pironi.verification;

public interface VerificationGate {
    void markChanged();

    boolean required();

    VerificationResult verifyIfRequired();
}
