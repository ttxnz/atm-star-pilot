package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.world.item.crafting.RecipeHolder;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** Reads the ordinary synchronized recipe object when Mek's JEI layout omits its chemical widget. */
public final class MekRecipes implements PilotController.Job {
    private final String id;
    public MekRecipes(JsonObject data){id=Jsons.str(data,"recipe","");}
    public String description(){return "Read native Mekanism chemical requirements: "+id;}
    public boolean tick(PilotController controller)throws Exception{
        var entry=JeiBridge.entry(id);Object raw=entry.raw();Object recipe=raw instanceof RecipeHolder<?> holder?holder.value():raw;
        if(Class.forName("mekanism.api.recipes.PressurizedReactionRecipe").isInstance(recipe)){
            Object ingredient=recipe.getClass().getMethod("getInputChemical").invoke(recipe);JsonArray chemicals=new JsonArray(),outputs=new JsonArray();
            for(Object stack:(List<?>)ingredient.getClass().getMethod("getRepresentations").invoke(ingredient))chemicals.add(Jsons.obj("id",stack.getClass().getMethod("getTypeRegistryName").invoke(stack).toString(),"amount",stack.getClass().getMethod("getAmount").invoke(stack)));
            for(Object output:(List<?>)recipe.getClass().getMethod("getOutputDefinition").invoke(recipe)){
                Object chemical=output.getClass().getMethod("chemical").invoke(output);
                outputs.add(Jsons.obj("item",WorldView.stack((net.minecraft.world.item.ItemStack)output.getClass().getMethod("item").invoke(output)),"chemical",Jsons.obj("id",chemical.getClass().getMethod("getTypeRegistryName").invoke(chemical).toString(),"amount",chemical.getClass().getMethod("getAmount").invoke(chemical))));
            }
            var report=Jsons.obj("recipe",id,"nativeClass",recipe.getClass().getName(),"chemicalAlternatives",chemicals,"outputs",outputs,"duration",recipe.getClass().getMethod("getDuration").invoke(recipe),"energy",recipe.getClass().getMethod("getEnergyRequired").invoke(recipe));
            Files.writeString(controller.directory.resolve("mek-recipe-"+id.replaceAll("[^a-zA-Z0-9._-]","_")+".json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);return true;
        }
        Class<?> type=Class.forName("mekanism.api.recipes.ItemStackChemicalToObjectRecipe");
        if(!type.isInstance(recipe))throw new IllegalArgumentException("This inspector supports native item-and-chemical Mekanism recipes");
        Object ingredient=type.getMethod("getChemicalInput").invoke(recipe);
        Class<?> ingredientType=Class.forName("mekanism.api.recipes.ingredients.ChemicalStackIngredient"),stackType=Class.forName("mekanism.api.chemical.ChemicalStack");
        JsonArray alternatives=new JsonArray();
        for(Object stack:(List<?>)ingredientType.getMethod("getRepresentations").invoke(ingredient))
            alternatives.add(Jsons.obj("id",stackType.getMethod("getTypeRegistryName").invoke(stack).toString(),"amount",stackType.getMethod("getAmount").invoke(stack)));
        JsonObject report=Jsons.obj("observedAt",Instant.now().toString(),"recipe",id,"nativeClass",recipe.getClass().getName(),
            "itemInputs",entry.recipe().inputs(),"chemicalAlternatives",alternatives,"perTickUsage",type.getMethod("perTickUsage").invoke(recipe),"outputs",entry.recipe().outputs());
        Files.writeString(controller.directory.resolve("last-mek-recipe.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);
        Files.writeString(controller.directory.resolve("mek-recipe-"+id.replaceAll("[^a-zA-Z0-9._-]","_")+".json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);
        controller.note("Native Mekanism chemical requirements saved; "+alternatives.size()+" alternatives");return true;
    }
}
