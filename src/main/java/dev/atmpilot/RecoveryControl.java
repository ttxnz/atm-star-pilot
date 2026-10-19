package dev.atmpilot;

/** Manual stops and repeated failures must survive creation of a new death checkpoint. */
public final class RecoveryControl {
    private RecoveryControl(){}
    public static boolean holdAfterNewDeath(boolean held,String priorState,int recentCycles,int maximumCycles){
        return (held&&!"recovered_needs_replan".equals(priorState))||recentCycles>maximumCycles;
    }
}
