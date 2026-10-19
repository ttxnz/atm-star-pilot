package dev.atmpilot;

/** A paused action explicitly states whether it can safely adapt to the post-combat position. */
public interface ReflexResumable {
    boolean resumeAfterLocalCombat();
}
