package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Reads synchronized native bee spawning recipes, which JEI's custom bee output is not an ItemStack. */
public final class BeeSpawningInspect implements PilotController.Job {
    private final String item;private final BlockPos position;
    public BeeSpawningInspect(JsonObject data){item=Jsons.str(data,"item","productivebees:oak_wood_nest");position=data.has("x")?Actions.pos(data):null;}
    public String description(){return "Read live nest spawning recipes and the loaded target biome";}
    @SuppressWarnings({"rawtypes","unchecked"})
    public boolean tick(PilotController c)throws Exception {
        var mc=Actions.mc();BlockPos p=position==null?mc.player.blockPosition():position;if(!LoadedWorld.has(p))throw new IllegalStateException("Target biome is not loaded");
        var key=ResourceLocation.parse(item);if(!BuiltInRegistries.ITEM.containsKey(key))throw new IllegalArgumentException("Unknown nest item");ItemStack sample=new ItemStack(BuiltInRegistries.ITEM.get(key));
        var biome=mc.level.getBiome(p);JsonArray rows=new JsonArray();var recipeKey=ResourceLocation.parse("productivebees:bee_spawning");
        if(!BuiltInRegistries.RECIPE_TYPE.containsKey(recipeKey))throw new IllegalStateException("Productive Bees native spawning type is not registered");RecipeType type=BuiltInRegistries.RECIPE_TYPE.get(recipeKey);
        for(Object value:mc.level.getRecipeManager().getAllRecipesFor(type)){
            RecipeHolder<?> holder=(RecipeHolder<?>)value;Object recipe=holder.value();if(!recipe.getClass().getName().equals("cy.jdkdigital.productivebees.common.recipe.BeeSpawningRecipe"))continue;
            Ingredient nest=(Ingredient)recipe.getClass().getField("ingredient").get(recipe);if(!nest.test(sample))continue;Ingredient lure=(Ingredient)recipe.getClass().getField("spawnItem").get(recipe);
            JsonArray lures=new JsonArray(),bees=new JsonArray();boolean matches=false;
            for(ItemStack candidate:lure.getItems()){lures.add(WorldView.stack(candidate));if((Boolean)recipe.getClass().getMethod("matches",ItemStack.class,ItemStack.class,Holder.class).invoke(recipe,sample,candidate,biome))matches=true;}
            for(Object supplied:(List<?>)recipe.getClass().getField("output").get(recipe)){
                Object bee=((Supplier<?>)supplied).get();EntityType<?> entity=(EntityType<?>)bee.getClass().getMethod("getBeeEntity").invoke(bee);Object species=bee.getClass().getMethod("getBeeType").invoke(bee);
                bees.add(Jsons.obj("entity",BuiltInRegistries.ENTITY_TYPE.getKey(entity).toString(),"species",species==null?null:species.toString(),"configurable",bee.getClass().getMethod("isConfigurable").invoke(bee)));
            }
            rows.add(Jsons.obj("recipe",holder.id().toString(),"matchesCurrentBiomeAndLure",matches,"lures",lures,"bees",bees));
        }
        JsonObject report=Jsons.obj("observedAt",Instant.now().toString(),"dimension",mc.level.dimension().location().toString(),"x",p.getX(),"y",p.getY(),"z",p.getZ(),"nestItem",item,
            "biome",biome.unwrapKey().map(k->k.location().toString()).orElse("unregistered"),"overworldBiomeTag",biome.is(TagKey.create(Registries.BIOME,ResourceLocation.parse("c:is_overworld"))),"recipes",rows,
            "note","Only client-synchronized recipes and loaded biome data were read. No bee was spawned, no nest inventory was inspected, and recipe compatibility is not proof of actual spawning.");
        Files.writeString(c.directory.resolve("last-bee-spawning-inspection.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);return true;
    }
}
