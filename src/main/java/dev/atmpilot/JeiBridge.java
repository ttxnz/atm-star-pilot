package dev.atmpilot;

import com.google.gson.*;
import mezz.jei.api.*;
import mezz.jei.api.ingredients.*;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.*;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import dev.atmpilot.PlanModel.Ingredient;
import java.util.*;
import static dev.atmpilot.PlanModel.*;

@JeiPlugin
public final class JeiBridge implements IModPlugin {
    private static IJeiRuntime runtime;
    private static final Map<String,Entry> entries=new LinkedHashMap<>();
    private static Iterator<IRecipeCategory<?>> categories=Collections.emptyIterator();
    private static Iterator<?> pending=Collections.emptyIterator();
    private static IRecipeCategory<?> category;
    private static boolean indexing;
    private static int failures;
    public record Entry(PlanModel.Recipe recipe,IRecipeCategory<?> category,Object raw) {}
    @Override public ResourceLocation getPluginUid() { return ResourceLocation.fromNamespaceAndPath("atmpilot","catalog"); }
    @Override public void onRuntimeAvailable(IJeiRuntime value) {
        runtime=value; entries.clear(); pending=Collections.emptyIterator(); failures=0;
        List<IRecipeCategory<?>> ordered=new ArrayList<>(),remaining=new ArrayList<>();
        for(var candidate:value.getRecipeManager().createRecipeCategoryLookup().get().toList()){
            if(candidate.getRecipeType().getUid().getNamespace().contains("modular"))ordered.add(candidate);else remaining.add(candidate);
        }
        ordered.addAll(remaining);categories=ordered.iterator();indexing=true;
    }
    @Override public void onRuntimeUnavailable() {
        runtime=null; entries.clear(); indexing=false; categories=Collections.emptyIterator(); pending=Collections.emptyIterator();
    }
    public static JsonObject status() { return Jsons.obj("available",runtime!=null,"indexing",indexing,"recipes",entries.size(),"unreadable",failures,
        "category",category==null?null:category.getRecipeType().getUid().toString(),
        "scope","material-producing recipes","excludedCategories",List.of("generatorgalore:solid_fuels","ironfurnaces:generator_*")); }
    public static Collection<Entry> entries() { return Collections.unmodifiableCollection(entries.values()); }
    public static Entry entry(String id) {
        Entry e=entries.get(id); if(e==null) throw new IllegalArgumentException("Unknown recipe: "+id); return e;
    }
    public static boolean ready() { return runtime!=null&&!indexing; }
    public static void tick() {
        if(!indexing||runtime==null||Minecraft.getInstance().level==null) return;
        // This category only displays fuel-to-energy conversions and has no material output.
        if(category!=null&&(category.getRecipeType().getUid().toString().equals("generatorgalore:solid_fuels")
            ||category.getRecipeType().getUid().toString().startsWith("ironfurnaces:generator_"))){
            pending=Collections.emptyIterator();
            List<IRecipeCategory<?>> priority=new ArrayList<>(),remaining=new ArrayList<>();
            while(categories.hasNext()){
                var candidate=categories.next();
                if(candidate.getRecipeType().getUid().getNamespace().contains("modular"))priority.add(candidate);else remaining.add(candidate);
            }
            priority.addAll(remaining);categories=priority.iterator();
        }
        long deadline=System.nanoTime()+4_000_000;
        int budget=40;
        while(budget-->0&&System.nanoTime()<deadline) {
            if(!pending.hasNext()) {
                if(!categories.hasNext()) { indexing=false; return; }
                category=categories.next(); pending=lookup(category).iterator(); continue;
            }
            Object raw=pending.next();
            try { Entry entry=read(category,raw); if(entry!=null) entries.put(entry.recipe.id(),entry); }
            catch(Exception|LinkageError e) { failures++; if(failures<=5||failures%500==0) PilotMod.LOG.warn("Cannot read JEI recipe in {}",category.getRecipeType().getUid(),e); }
        }
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static List<?> lookup(IRecipeCategory category) {
        if(category.getRecipeType().getUid().toString().equals("generatorgalore:solid_fuels")
            ||category.getRecipeType().getUid().toString().startsWith("ironfurnaces:generator_"))return List.of();
        return runtime.getRecipeManager().createRecipeLookup(category.getRecipeType()).get().toList();
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static Entry read(IRecipeCategory<?> untypedCategory,Object raw) {
        IRecipeCategory<Object> category=(IRecipeCategory<Object>)untypedCategory;
        var manager=runtime.getRecipeManager();
        var layout=manager.createRecipeLayoutDrawable(category,raw,runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup());
        if(layout.isEmpty()) return null;
        var slots=layout.get().getRecipeSlotsView();
        List<Ingredient> inputs=new ArrayList<>(); List<Amount> outputs=new ArrayList<>();
        boolean ambiguous=false;
        for(var slot:slots.getSlotViews()) {
            List<Amount> ingredients=new ArrayList<>();
            for(var typed:slot.getAllIngredientsList()) {
                Amount a=amount(typed); if(a!=null&&!ingredients.contains(a)) ingredients.add(a);
            }
            if(ingredients.isEmpty()) continue;
            if(slot.getRole()==RecipeIngredientRole.INPUT) inputs.add(new Ingredient(ingredients));
            if(slot.getRole()==RecipeIngredientRole.OUTPUT) {
                // A cycling output is an alternative, not several guaranteed byproducts.
                if(ingredients.size()!=1) ambiguous=true;
                outputs.add(ingredients.getFirst());
            }
        }
        if(outputs.isEmpty()||inputs.isEmpty()) return null;
        String type=category.getRecipeType().getUid().toString();
        ResourceLocation registryName=category.getRegistryName(raw);
        if(registryName==null&&raw instanceof RecipeHolder<?> holder) registryName=holder.id();
        String id=type+"|"+(registryName==null?Integer.toHexString(Objects.hash(inputs,outputs)):registryName);
        Object value=raw instanceof RecipeHolder<?> holder?holder.value():raw;
        boolean vanillaCraft=type.equals("minecraft:crafting")&&(value instanceof ShapedRecipe||value instanceof ShapelessRecipe);
        boolean cooking=Set.of("minecraft:smelting","minecraft:blasting","minecraft:smoking","minecraft:campfire").contains(type)&&value instanceof AbstractCookingRecipe;
        boolean chance=hasChance(value);
        List<Ingredient> catalysts=new ArrayList<>();
        List<Amount> catalystAlternatives=new ArrayList<>();
        manager.createRecipeCatalystLookup(category.getRecipeType()).get().forEach(t -> {
            Amount a=amount((ITypedIngredient<?>)t); if(a!=null&&!catalystAlternatives.contains(a)) catalystAlternatives.add(a);
        });
        if(!catalystAlternatives.isEmpty()) catalysts.add(new Ingredient(catalystAlternatives));
        String note=vanillaCraft||cooking?"Live JEI recipe":"Machine recipe; requires a configured execution adapter";
        if(chance) note="Chance output: expected quantities are not guaranteed";
        if(ambiguous) note="Ambiguous cycling output: requires manual recipe classification";
        var recipe=new PlanModel.Recipe(id,type,inputs,outputs,catalysts,!chance&&!ambiguous,vanillaCraft||cooking,note);
        return new Entry(recipe,category,raw);
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static Amount amount(ITypedIngredient<?> typed) {
        if(typed==null)return null; // Some smithing displays intentionally include an empty alternative.
        Object value=typed.getIngredient();
        if(value instanceof ItemStack stack) return stack.isEmpty()?null:new Amount(WorldView.material(stack),stack.getCount());
        IIngredientHelper helper=runtime.getIngredientManager().getIngredientHelper(typed.getType());
        long count=helper.getAmount(value); if(count<=0) return null;
        String kind=value.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String uid=helper.getUniqueId(value,UidContext.Recipe);
        return new Amount(new Material(kind+":"+helper.getResourceLocation(value),uid),count);
    }
    private static boolean hasChance(Object value) {
        // Known probability getters only. Never invoke arbitrary methods or fields.
        for(String name:List.of("getChance","chance","getProbability","probability","getSecondaryChance","getSecondaryOutputChance")) {
            try {
                var method=value.getClass().getMethod(name);
                if(Number.class.isAssignableFrom(method.getReturnType())||method.getReturnType().isPrimitive()) {
                    Object result=method.invoke(value);
                    if(result instanceof Number number&&number.doubleValue()>0&&number.doubleValue()<1) return true;
                }
            } catch(ReflectiveOperationException ignored) {}
        }
        return false;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    public static void transfer(String id,boolean max) {
        ServerRules.checkScreenAction();
        if(runtime==null) throw new IllegalStateException("JEI is not ready");
        var mc=Minecraft.getInstance(); var entry=entry(id);
        if(mc.player==null) throw new IllegalStateException("Not connected");
        ServerRules.checkScreenAction();
        var menu=mc.player.containerMenu;
        if(!menu.getCarried().isEmpty()) throw new IllegalStateException("Clear the carried stack before recipe transfer");
        var category=(IRecipeCategory<Object>)entry.category;
        var handler=runtime.getRecipeTransferManager().getRecipeTransferHandler(menu,category)
            .orElseThrow(() -> new IllegalStateException("No JEI transfer handler for this menu and recipe"));
        var layout=runtime.getRecipeManager().createRecipeLayoutDrawable(category,entry.raw,
            runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()).orElseThrow();
        var error=handler.transferRecipe(menu,entry.raw,layout.getRecipeSlotsView(),mc.player,max,false);
        if(error!=null&&!error.getType().allowsTransfer) throw new IllegalStateException("JEI transfer: "+error.getTooltip()+" ["+error.getType()+"]");
        error=handler.transferRecipe(menu,entry.raw,layout.getRecipeSlotsView(),mc.player,max,true);
        if(error!=null&&!error.getType().allowsTransfer) throw new IllegalStateException("JEI transfer failed: "+error.getTooltip());
    }
}
