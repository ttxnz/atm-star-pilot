package dev.atmpilot;

/** A known local cooldown: no command has been sent, so waiting is safe. */
public final class SurvivalCommandCooldown extends IllegalStateException {
    private final long remainingMillis;
    public SurvivalCommandCooldown(long remainingNanos){
        super("Wait for the survival-command cooldown; no command was sent");
        remainingMillis=Math.max(1,(remainingNanos+999_999L)/1_000_000L);
    }
    public long remainingMillis(){return remainingMillis;}
}
