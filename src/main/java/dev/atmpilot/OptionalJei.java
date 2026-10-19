package dev.atmpilot;

import com.google.gson.JsonObject;
import net.neoforged.fml.ModList;

/** Keep JEI's interfaces out of the class-loading path of a vanilla-only client. */
public final class OptionalJei {
    private OptionalJei() {}
    public static boolean installed() { return ModList.get().isLoaded("jei"); }
    public static void require() { if (!installed()) throw new IllegalStateException("This ATM recipe action requires JEI; vanilla mode uses the server recipe book instead"); }
    public static JsonObject status() { return installed() ? JeiBridge.status() : Jsons.obj("available", false, "optional", true, "source", "vanilla recipe book"); }
    public static void tick() { if (installed()) JeiBridge.tick(); }
}
