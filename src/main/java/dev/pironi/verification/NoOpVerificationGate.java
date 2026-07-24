package dev.pironi.verification;

public final class NoOpVerificationGate implements VerificationGate {
    @Override
    public void markChanged() {
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    public VerificationResult verifyIfRequired() {
        return VerificationResult.notRequired();
    }
}
