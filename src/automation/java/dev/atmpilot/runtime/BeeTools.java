package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.crafting.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Synchronized bee recipes and public nest timers; never constructs or spawns an entity. */
public final class BeeTools {
    private BeeTools(){}
    private static JsonObject bee(Object supplier)throws Exception {
        Object value=((Supplier<?>)supplier).get();EntityType<?> type=(EntityType<?>)value.getClass().getMethod("getBeeEntity").invoke(value);Object species=value.getClass().getMethod("getBeeType").invoke(value);
        return Jsons.obj("entity",BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(),"species",species==null?null:species.toString(),"configurable",value.getClass().getMethod("isConfigurable").invoke(value));
    }
    private static void save(PilotController c,String name,JsonObject report)throws Exception {Files.writeString(c.directory.resolve(name),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);}
    public static final class NestInspect implements PilotController.Job {
        private final JsonObject data;public NestInspect(JsonObject data){this.data=data.deepCopy();}
        public String description(){return "Read own nest public synchronized cooldown and spawn counter";}
        public boolean tick(PilotController c)throws Exception {
            var mc=Actions.mc();var positions=data.has("positions")?data.getAsJsonArray("positions"):new JsonArray();if(!data.has("positions"))positions.add(data);if(positions.isEmpty()||positions.size()>64)throw new IllegalArgumentException("Expected 1..64 nest positions");JsonArray rows=new JsonArray();
            for(var entry:positions){var p=Actions.pos(entry.getAsJsonObject());ServerRules.checkBuild(p);if(!LoadedWorld.has(p))throw new IllegalStateException("Nest chunk is not loaded");var entity=mc.level.getBlockEntity(p);
                if(entity==null||!entity.getClass().getName().equals("cy.jdkdigital.productivebees.common.block.entity.SolitaryNestBlockEntity"))throw new IllegalStateException("Expected an observed own solitary nest");
                rows.add(Jsons.obj("x",p.getX(),"y",p.getY(),"z",p.getZ(),"block",BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString(),"state",mc.level.getBlockState(p).toString(),"cachedCooldownTicks",entity.getClass().getMethod("getNestTickCooldown").invoke(entity),"cachedSpawnCount",entity.getClass().getMethod("getSpawnCount").invoke(entity)));
            }
            save(c,"last-bee-nest-inspection.json",Jsons.obj("observedAt",Instant.now().toString(),"dimension",mc.level.dimension().location().toString(),"dayTime",mc.level.getDayTime(),"nests",rows,"note","Public packet values can remain cached until another native update; they are not a continuously synchronized remaining-time guarantee. Verify visible entities and actual filled cages. No hidden bee inventory or spawning method was accessed."));return true;
        }
    }
    public static final class Recipes implements PilotController.Job {
        private final String filter;private final int limit;public Recipes(JsonObject data){filter=Jsons.str(data,"filter","").toLowerCase(Locale.ROOT);limit=Jsons.integer(data,"limit",128);if(limit<1||limit>512||filter.length()>128)throw new IllegalArgumentException("Unsupported bee recipe query size");}
        public String description(){return "Read native synchronized breeding and conversion dependencies";}
        @SuppressWarnings({"rawtypes","unchecked"})
        public boolean tick(PilotController c)throws Exception {
            var mc=Actions.mc();JsonArray rows=new JsonArray();int total=0;
            for(String kind:List.of("bee_breeding","bee_conversion")){
                var key=ResourceLocation.parse("productivebees:"+kind);if(!BuiltInRegistries.RECIPE_TYPE.containsKey(key))continue;RecipeType type=BuiltInRegistries.RECIPE_TYPE.get(key);
                for(Object raw:mc.level.getRecipeManager().getAllRecipesFor(type)){
                    RecipeHolder<?> holder=(RecipeHolder<?>)raw;Object r=holder.value();JsonObject row=Jsons.obj("recipe",holder.id().toString(),"type",key.toString());
                    if(kind.equals("bee_breeding")){
                        row.add("parent1",bee(r.getClass().getField("parent1").get(r)));row.add("parent2",bee(r.getClass().getField("parent2").get(r)));row.add("offspring",bee(r.getClass().getField("offspring").get(r)));row.addProperty("parentDeathChance",r.getClass().getField("parentDeathChance").getFloat(r));row.addProperty("processingTicks",((Number)r.getClass().getMethod("getProcessingTime").invoke(r)).intValue());
                    }else{
                        row.add("source",bee(r.getClass().getField("source").get(r)));row.add("result",bee(r.getClass().getField("result").get(r)));row.addProperty("chance",r.getClass().getField("chance").getFloat(r));JsonArray items=new JsonArray();for(var stack:((Ingredient)r.getClass().getField("item").get(r)).getItems())items.add(WorldView.stack(stack));row.add("items",items);
                    }
                    if(!filter.isEmpty()&&!row.toString().toLowerCase(Locale.ROOT).contains(filter))continue;total++;if(rows.size()<limit)rows.add(row);
                }
            }
            JsonObject report=Jsons.obj("observedAt",Instant.now().toString(),"filter",filter,"total",total,"truncated",total>rows.size(),"recipes",rows,"note","Only synchronized recipe metadata was read. No cached entity constructor, breeding or conversion mutation was called.");save(c,"last-bee-recipes.json",report);save(c,"bee-recipes-"+filter.replaceAll("[^a-z0-9_-]","_")+".json",report);return true;
        }
    }
}
