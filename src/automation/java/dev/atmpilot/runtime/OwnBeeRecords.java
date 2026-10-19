package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Pins bees first observed beside an own nest, allowing their ordinary retrieval after they fly away. */
public final class OwnBeeRecords {
    private OwnBeeRecords(){}
    private static JsonObject entries;
    private static JsonObject entries(){if(entries==null){var p=PilotMod.CONTROLLER.directory.resolve("own-bee-origins.json");try{entries=Files.isRegularFile(p)?JsonParser.parseString(Files.readString(p,StandardCharsets.UTF_8)).getAsJsonObject():new JsonObject();}catch(Exception e){throw new IllegalStateException("Cannot read own bee identity records",e);}}return entries;}
    public static boolean tracked(Entity entity){
        if(entity==null)return false;JsonObject row=entries().getAsJsonObject(entity.getUUID().toString());if(row==null)return false;var mc=Actions.mc();
        if(!mc.level.dimension().location().toString().equals(Jsons.str(row,"dimension",""))||!BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(Jsons.str(row,"type","")))return false;
        BlockPos nest=Actions.pos(row);try{ServerRules.checkBuild(nest);}catch(IllegalStateException denied){return false;}
        return LoadedWorld.has(nest)&&BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(nest).getBlock()).toString().equals(Jsons.str(row,"nestBlock",""))&&entity.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(nest))<=64*64;
    }
    public static boolean register(Entity entity,JsonArray nests){
        if(tracked(entity))return true;if(entity==null||entries().size()>=256)return false;try{ServerRules.checkBuild(entity.blockPosition());}catch(IllegalStateException denied){return false;}var mc=Actions.mc();
        for(var value:nests){BlockPos p=Actions.pos(value.getAsJsonObject());ServerRules.checkBuild(p);if(!LoadedWorld.has(p)||entity.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p))>6*6)continue;String block=BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString();if(!block.endsWith("_nest"))continue;
            entries().add(entity.getUUID().toString(),Jsons.obj("dimension",mc.level.dimension().location().toString(),"type",BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),"x",p.getX(),"y",p.getY(),"z",p.getZ(),"nestBlock",block,"firstObservedAt",Instant.now().toString(),"source","Observed inside the approved new nursery within six blocks of an own starter nest"));
            try{Files.writeString(PilotMod.CONTROLLER.directory.resolve("own-bee-origins.json"),Jsons.GSON.toJson(entries()),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("Cannot persist bee origin",e);}return true;
        }return false;
    }
}
