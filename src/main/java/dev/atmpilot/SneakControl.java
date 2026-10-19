package dev.atmpilot;

/** Both native vertical keys cancel each other in existing flight while preserving real sneak state. */
public final class SneakControl {
    private SneakControl(){}
    public static void hold(){
        var mc=Actions.mc();mc.options.keyShift.setDown(true);
        if(mc.player.getAbilities().flying)mc.options.keyJump.setDown(true);
    }
}
