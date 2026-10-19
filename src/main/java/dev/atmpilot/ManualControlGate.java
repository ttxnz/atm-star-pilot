package dev.atmpilot;

public final class ManualControlGate {
    private ManualControlGate() {}
    public static void require(boolean held, Object first) {
        if (held && (!(first instanceof ManualResumeJob resume) || !resume.explicitlyResumesManualControl()))
            throw new IllegalStateException("Manual control is held; only an explicitly authorized recovery_resume may restart automation");
    }
}
