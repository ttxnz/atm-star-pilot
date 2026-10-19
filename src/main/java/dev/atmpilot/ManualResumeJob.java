package dev.atmpilot;

/** Only an explicit checkpoint-resume adapter may enter a session held by the player. */
public interface ManualResumeJob {
    boolean explicitlyResumesManualControl();
}
