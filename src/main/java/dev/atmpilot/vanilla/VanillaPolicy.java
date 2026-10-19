package dev.atmpilot.vanilla;

import java.util.Set;

/** Shared policy: the vanilla runner never calls the ATM command/flight adapters. */
public final class VanillaPolicy {
    private VanillaPolicy() {}
    public static final Set<String> DIMENSIONS=Set.of("minecraft:overworld","minecraft:the_nether","minecraft:the_end");
    public static final Set<String> STAGES=Set.of("bootstrap","iron","equipment","portal","blaze_rods","pearls","stronghold","end_portal","dragon","exit","complete");
    public static void validateStart(String actual,String confirmation,boolean allowed,boolean survival,boolean vanilla,boolean fresh,boolean resume) {
        if(!allowed||actual.isBlank()||!actual.equals(confirmation))throw new IllegalArgumentException("Confirm the exact dedicated world key and allowWorldChanges before starting");
        if(!survival)throw new IllegalArgumentException("Vanilla mode requires Survival difficulty/game mode, without creative flight");
        if(!vanilla)throw new IllegalArgumentException("This mode requires vanilla content; use a separate client instance without gameplay mods");
        if(!resume&&!fresh)throw new IllegalArgumentException("A new zero-to-dragon run requires an empty player inventory; use resume for this world's checkpoint");
    }
    public static boolean permitsAction(String action) {
        return Set.of("walk","mine","place","craft","smelt","eat","equip","melee","shoot","eye","portal","respawn","collect","wait","interact").contains(action);
    }
}
