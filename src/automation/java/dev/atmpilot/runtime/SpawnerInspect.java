package dev.atmpilot.runtime;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Read-only production observation of an owned, loaded spawner and its local light. */
public final class SpawnerInspect implements PilotController.Job {
    private final BlockPos position;
    public SpawnerInspect(JsonObject data){position=Actions.pos(data);}
    public String description(){return "Read owned spawner client data and loaded light conditions";}
    @SuppressWarnings({"rawtypes","unchecked"})
    public boolean tick(PilotController controller)throws Exception {
        var mc=Actions.mc();ServerRules.checkBuild(position);
        if(!LoadedWorld.has(position)||!(mc.level.getBlockEntity(position) instanceof SpawnerBlockEntity tile))
            throw new IllegalStateException("Expected a loaded owned spawner");
        JsonArray samples=new JsonArray();
        for(int x=position.getX()-3;x<=position.getX()+3;x++)for(int z=position.getZ()-3;z<=position.getZ()+3;z++){
            var p=new BlockPos(x,position.getY(),z);if(!LoadedWorld.has(p))continue;
            samples.add(Jsons.obj("x",x,"y",p.getY(),"z",z,"block",BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString(),
                "skyLight",mc.level.getBrightness(LightLayer.SKY,p),"blockLight",mc.level.getBrightness(LightLayer.BLOCK,p)));
        }
        var report=Jsons.obj("observedAt",Instant.now().toString(),"dimension",mc.level.dimension().location().toString(),
            "x",position.getX(),"y",position.getY(),"z",position.getZ(),"difficulty",mc.level.getDifficulty().toString(),
            "playerDistance",Math.sqrt(position.distToCenterSqr(mc.player.position())),"clientSpawnerData",tile.saveWithoutMetadata(mc.level.registryAccess()).toString(),
            "lightSamples",samples,"note","Client-synchronized read-only data. Client animation delay is not authoritative server spawn timing.");
        JsonArray modifiers=new JsonArray();
        var modifierId=ResourceLocation.parse("apothic_spawners:spawner_modifier");
        if(BuiltInRegistries.RECIPE_TYPE.containsKey(modifierId)){
            RecipeType type=BuiltInRegistries.RECIPE_TYPE.get(modifierId);
            for(Object value:mc.level.getRecipeManager().getAllRecipesFor(type)){
                RecipeHolder<?> holder=(RecipeHolder<?>)value;
                JsonElement recipe=Recipe.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,mc.level.registryAccess()),holder.value()).getOrThrow();
                if(recipe.toString().contains("ignore_conditions")||recipe.toString().contains("ignore_light")||recipe.toString().contains("ignore_players"))
                    modifiers.add(Jsons.obj("id",holder.id().toString(),"recipe",recipe));
            }
        }
        report.add("liveConditionModifiers",modifiers);
        Files.writeString(controller.directory.resolve("last-spawner-inspection.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);return true;
    }
}
