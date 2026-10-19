package dev.atmpilot;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/** Narrow permission for using a user-confirmed existing pad; it grants no construction/configuration rights. */
public final class TransportRules {
    private TransportRules(){}
    public static JsonObject site(BlockPos p){var mc=Actions.mc();var rows=ServerRules.snapshot().getAsJsonArray("verifiedTransportSites");if(rows==null)return null;for(var value:rows){var row=value.getAsJsonObject();if(Jsons.str(row,"dimension","").equals(mc.level.dimension().location().toString())&&Actions.pos(row).equals(p)&&Jsons.bool(row,"existingExitConfirmed",false)&&Jsons.str(row,"block","").equals("allthemodium:teleport_pad"))return row.deepCopy();}return null;}
    public static boolean allows(BlockPos p){
        var row=site(p);if(row==null)return false;var mc=Actions.mc();if(!LoadedWorld.has(p)||!BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString().equals("allthemodium:teleport_pad"))throw new IllegalStateException("Confirmed existing transport pad changed");
        if(!mc.player.getMainHandItem().isEmpty()||!mc.player.getOffhandItem().isEmpty()||!mc.player.isCrouching())throw new IllegalStateException("Use the confirmed pad with empty hands and ordinary crouching");return true;
    }
}
