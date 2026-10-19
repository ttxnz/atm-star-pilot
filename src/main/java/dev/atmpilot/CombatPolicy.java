package dev.atmpilot;

import java.util.Set;

/** Deliberately excludes players, animals, neutral mobs, bosses, and unknown modded species. */
public final class CombatPolicy {
    private CombatPolicy() {}
    private static final Set<String> HOSTILES = Set.of(
        "minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager", "minecraft:creeper",
        "minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:wither_skeleton",
        "minecraft:spider", "minecraft:cave_spider", "minecraft:silverfish", "minecraft:endermite",
        "minecraft:slime", "minecraft:magma_cube", "minecraft:blaze", "minecraft:phantom",
        "minecraft:witch", "minecraft:pillager", "minecraft:vindicator", "minecraft:vex");
    public static boolean permitted(String type, boolean named, boolean passenger, boolean noAi) {
        return (HOSTILES.contains(type) || type.equals("minecraft:ghast") || type.equals("minecraft:creeper")) && !named && !passenger && !noAi;
    }
    public static boolean canHit(boolean visible, boolean inReach, boolean bystander, double strength, long sinceSwing) {
        return visible && inReach && !bystander && strength >= .95 && sinceSwing >= 4;
    }
    public static boolean shouldEscape(double health, double threshold, boolean creeper, int threats) {
        return health <= threshold;
    }
}
