package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Encodes observed item recipes using normal JEI, ghost-slot clicks and the native encode button. */
public final class AeProcessingEncode implements PilotController.Job {
    private final String recipeId;
    private final String suppliedFluid;private final long suppliedFluidAmount;
    private final PlanModel.Material suppliedItem;
    private Object menu;
    private PlanModel.Recipe recipe;
    private PlanModel.Material pattern;
    private final List<PlanModel.Ingredient> expectedInputs=new ArrayList<>();
    private final Set<PlanModel.Material> persistentCatalysts=new HashSet<>();
    private final Map<String,Long> suppliedChemicals=new HashMap<>();
    private Slot blank,encoded;
    private int phase,elapsed,wait,blankBefore,pendingGhost=-1;
    private long before;
    private final String preferredGearId;
    private boolean preferredGearCopied;
    public AeProcessingEncode(JsonObject data){recipeId=Jsons.str(data,"recipe","");JsonObject fluid=data.has("suppliedFluid")?data.getAsJsonObject("suppliedFluid"):null;suppliedFluid=fluid==null?"":Jsons.str(fluid,"id","");suppliedFluidAmount=fluid==null?0:fluid.get("amount").getAsLong();if(fluid!=null&&(!Set.of("minecraft:water","minecraft:lava","advanced_ae:quantum_infusion_source","industrialforegoing:latex","industrialforegoing:pink_slime","industrialforegoing:ether_gas","industrialforegoing:essence","justdirethings:unstable_portal_fluid_source").contains(suppliedFluid)||suppliedFluidAmount<1||suppliedFluidAmount>64000))throw new IllegalArgumentException("An explicit observed supplied fluid and amount are required");
        preferredGearId=Jsons.str(data,"preferredGear",Jsons.bool(data,"preferAtoIronGear",false)?"alltheores:iron_gear":"");
        if(!preferredGearId.isEmpty()&&!(recipeId.equals("industrialforegoing:dissolution|ed06396e")&&preferredGearId.equals("alltheores:iron_gear")||recipeId.equals("industrialforegoing:dissolution|5d245a06")&&preferredGearId.equals("alltheores:diamond_gear")))throw new IllegalArgumentException("The carried gear preference is restricted to the two observed soul manufacturing recipes");
        JsonObject extra=data.has("suppliedItem")?data.getAsJsonObject("suppliedItem"):null;
        suppliedItem=extra==null?null:new PlanModel.Material(Jsons.str(extra,"id",""),Jsons.str(extra,"components",""));
        if(extra!=null&&(!recipeId.equals("mekanism:combining|mekanism:processing/netherite/dust_to_ancient_debris")||!suppliedItem.equals(new PlanModel.Material("minecraft:basalt",""))||Jsons.integer(extra,"count",0)!=1))
            throw new IllegalArgumentException("Only the observed separately supplied single basalt for netherite combining is supported");
    }
    public String description(){return "Encode own AE processing pattern from live recipe: "+recipeId+" (phase "+phase+")";}
    private static Class<?> api(String name)throws Exception{return Class.forName(name,true,PilotJobs.class.getClassLoader());}
    private static Object call(Object value,String method)throws Exception{return value.getClass().getMethod(method).invoke(value);}
    private static List<Slot> slots(Object menu,String name)throws Exception{
        Object semantic=api("appeng.menu.SlotSemantics").getField(name).get(null);
        List<?> nativeSlots=(List<?>)menu.getClass().getMethod("getSlots",api("appeng.menu.SlotSemantic")).invoke(menu,semantic);
        List<Slot> result=new ArrayList<>();for(Object value:nativeSlots)result.add((Slot)value);return result;
    }
    private void guard()throws Exception{
        var mc=Actions.mc();if(menu!=mc.player.containerMenu||mc.screen==null||!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Processing menu or cursor changed; no encode was repeated");
        ServerRules.checkScreenAction();Object host=call(menu,"getHost");BlockEntity block=(BlockEntity)call(host,"getBlockEntity");
        if(block.getLevel()!=mc.level)throw new IllegalStateException("Processing terminal dimension changed");
        ServerRules.checkBuild(block.getBlockPos());Actions.checkReach(block.getBlockPos());
    }
    private void save(String state)throws Exception{
        Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-ae-processing-encoding.json"),Jsons.GSON.toJson(Jsons.obj(
                "observedAt",Instant.now().toString(),"recipe",recipeId,"state",state,"phase",phase,"blankBefore",blankBefore,
                "blankNow",blank==null?-1:blank.getItem().getCount(),"inputs",expectedInputs,"pattern",pattern,
                "persistentCatalysts",persistentCatalysts,"suppliedItem",suppliedItem,
                "suppliedChemicals",suppliedChemicals,"suppliedFluid",suppliedFluid,"suppliedFluidAmount",suppliedFluidAmount,
                "chemicalSupply","Infusion chemicals are supplied separately by the dedicated machine; this pattern encodes item input/output only")),StandardCharsets.UTF_8);
    }
    private static ItemStack keyItem(Object generic)throws Exception{
        Object key=call(generic,"what");if(!api("appeng.api.stacks.AEItemKey").isInstance(key))throw new IllegalStateException("Only item processing patterns are supported");
        return (ItemStack)call(key,"toStack");
    }
    private List<Slot> nonempty(String semantic)throws Exception{return slots(menu,semantic).stream().filter(s->!s.getItem().isEmpty()).toList();}
    private Map<PlanModel.Material,Long> shownInputs()throws Exception{
        Map<PlanModel.Material,Long> result=new HashMap<>();
        for(Slot slot:nonempty("PROCESSING_INPUTS"))result.merge(WorldView.material(slot.getItem()),(long)slot.getItem().getCount(),Long::sum);
        return result;
    }
    private boolean clearPersistentCatalyst()throws Exception{
        var mc=Actions.mc();
        if(pendingGhost>=0){
            if(!mc.player.containerMenu.getSlot(pendingGhost).getItem().isEmpty())throw new IllegalStateException("Native ghost-slot clearing was not confirmed; the click was not repeated");
            pendingGhost=-1;
        }
        for(Slot slot:nonempty("PROCESSING_INPUTS"))if(persistentCatalysts.contains(WorldView.material(slot.getItem()))||WorldView.material(slot.getItem()).equals(suppliedItem)||suppliedChemicalGhost(slot.getItem())){
            // AE panels position their fake slots during rendering; an unfocused window may still
            // display the previous mode. Use the same native slot-click handler with the verified
            // semantic slot instead of projecting stale panel coordinates onto player/storage slots.
            var click=api("appeng.client.gui.AEBaseScreen").getDeclaredMethod("slotClicked",Slot.class,int.class,int.class,ClickType.class);click.setAccessible(true);click.invoke(mc.screen,slot,slot.index,0,ClickType.PICKUP);
            pendingGhost=slot.index;wait=10;save("persistent_catalyst_ghost_clear_sent_once");return true;
        }
        return false;
    }
    private boolean suppliedChemicalGhost(ItemStack stack){
        var material=WorldView.material(stack);
        if(!material.id().equals("ae2:wrapped_generic_stack"))return false;
        var wrapped=JsonParser.parseString(material.components()).getAsJsonObject().getAsJsonObject("ae2:wrapped_stack");
        if(wrapped!=null&&!suppliedFluid.isEmpty()&&Jsons.str(wrapped,"#t","").equals("ae2:f")){if(!Jsons.str(wrapped,"id","").equals(suppliedFluid)||!wrapped.has("#")||wrapped.get("#").getAsLong()!=suppliedFluidAmount)throw new IllegalStateException("JEI fluid ghost differs from the declared synchronized recipe");return true;}
        if(wrapped==null||!Jsons.str(wrapped,"#t","").equals("appmek:chemical"))return false;
        Long amount=suppliedChemicals.get(Jsons.str(wrapped,"id",""));
        if(amount==null||!wrapped.has("#")||wrapped.get("#").getAsLong()!=amount)throw new IllegalStateException("JEI chemical ghost differs from the synchronized Mek recipe");
        return true;
    }
    private void checkGhosts()throws Exception{
        List<Slot> inputs=nonempty("PROCESSING_INPUTS"),outputs=nonempty("PROCESSING_OUTPUTS");
        if(inputs.isEmpty()||outputs.size()!=1)throw new IllegalStateException("Visible item inputs/output differ from the live machine recipe");
        // AE combines repeated ingredients into one ghost stack (e.g. the two plastic
        // entries of a Dissolution recipe). Match complete ingredient quantities, not slot count.
        Map<PlanModel.Material,Long> remaining=new HashMap<>(shownInputs());
        for(var ingredient:expectedInputs){
            var choice=ingredient.alternatives().stream().filter(a->remaining.getOrDefault(a.material(),0L)>=a.count()).findFirst()
                    .orElseThrow(()->new IllegalStateException("Visible input differs from the live machine recipe"));
            remaining.compute(choice.material(),(k,v)->v-choice.count());
        }
        if(remaining.values().stream().anyMatch(n->n!=0))throw new IllegalStateException("Visible input contains extra recipe material");
        var product=recipe.outputs().getFirst();ItemStack shownOutput=outputs.getFirst().getItem();
        if(!WorldView.material(shownOutput).equals(product.material())||shownOutput.getCount()!=product.count())throw new IllegalStateException("Visible output differs from the live machine recipe");
    }
    private boolean copyPreferredGear()throws Exception{
        if(preferredGearId.isEmpty()||preferredGearCopied)return false;
        var mc=Actions.mc();var wanted=new PlanModel.Material(preferredGearId,"");
        long allowed=expectedInputs.stream().filter(i->i.alternatives().stream().anyMatch(a->a.material().equals(wanted)&&a.count()==1)).count();
        if(allowed!=(recipeId.endsWith("ed06396e")?2:1))throw new IllegalStateException("Live recipe no longer accepts the observed ATO gear quantity");
        Slot target=null,source=null;
        for(Slot s:nonempty("PROCESSING_INPUTS"))if(WorldView.material(s.getItem()).equals(wanted)){if(s.getItem().getCount()!=allowed)throw new IllegalStateException("Unexpected native gear quantity");preferredGearCopied=true;return false;}
        for(Slot s:nonempty("PROCESSING_INPUTS"))if(WorldView.material(s.getItem()).equals(new PlanModel.Material("industrialforegoing:"+preferredGearId.substring(preferredGearId.indexOf(':')+1),""))&&s.getItem().getCount()==allowed)target=s;
        for(Slot s:mc.player.containerMenu.slots)if(s.container==mc.player.getInventory()&&WorldView.material(s.getItem()).equals(wanted)&&s.getItem().getCount()==allowed){source=s;break;}
        if(target==null||source==null)throw new IllegalStateException("Carry one exact stack matching the native gear quantity before copying the accepted alternative");
        long countBefore=WorldView.count(wanted);
        Actions.click(mc.player.containerMenu.containerId,source.index,0,ClickType.PICKUP);
        var click=api("appeng.client.gui.AEBaseScreen").getDeclaredMethod("slotClicked",Slot.class,int.class,int.class,ClickType.class);click.setAccessible(true);
        click.invoke(mc.screen,target,target.index,0,ClickType.PICKUP);
        Actions.click(mc.player.containerMenu.containerId,source.index,0,ClickType.PICKUP);
        if(!mc.player.containerMenu.getCarried().isEmpty()||WorldView.count(wanted)!=countBefore)throw new IllegalStateException("Gear cursor return was not confirmed; preserve the checkpoint");
        preferredGearCopied=true;wait=10;save("preferred_carried_gear_copied");return true;
    }
    public boolean tick(PilotController controller)throws Exception{
        try{return step();}catch(Exception e){try{save("failed_needs_replan");}catch(Exception ignored){}throw e;}
    }
    private boolean step()throws Exception{
        var mc=Actions.mc();
        if(menu==null){
            menu=mc.player.containerMenu;guard();
            if(!menu.getClass().getName().equals("appeng.menu.me.items.PatternEncodingTermMenu"))throw new IllegalStateException("Open the own physical pattern encoding terminal");
            recipe=JeiBridge.entry(recipeId).recipe();
            if(!Set.of("mekanism:crushing","mekanism:enriching","mekanism:combining","mekanism:metallurgic_infusing","minecraft:smelting","ae2:inscriber","extendedae:crystal_assembler","extendedae:circuit_cutter","advanced_ae:reaction_chamber","industrialforegoing:dissolution").contains(recipe.type())
                    ||!recipe.deterministic()||recipe.inputs().isEmpty()||recipe.inputs().size()>9||recipe.outputs().size()!=1)throw new IllegalArgumentException("An observed deterministic item machine recipe is required");
            Object raw=JeiBridge.entry(recipeId).raw();if(raw instanceof RecipeHolder<?> holder)raw=holder.value();
            if(recipe.type().equals("mekanism:metallurgic_infusing")){
                Class<?> chemicalRecipe=api("mekanism.api.recipes.ItemStackChemicalToObjectRecipe");
                if(!chemicalRecipe.isInstance(raw)||(Boolean)chemicalRecipe.getMethod("perTickUsage").invoke(raw))throw new IllegalStateException("Only observed batch infusion chemicals may be supplied separately");
                Object ingredient=chemicalRecipe.getMethod("getChemicalInput").invoke(raw);
                Class<?> ingredientType=api("mekanism.api.recipes.ingredients.ChemicalStackIngredient"),stackType=api("mekanism.api.chemical.ChemicalStack");
                for(Object chemical:(List<?>)ingredientType.getMethod("getRepresentations").invoke(ingredient)){
                    String id=stackType.getMethod("getTypeRegistryName").invoke(chemical).toString();
                    long amount=((Number)stackType.getMethod("getAmount").invoke(chemical)).longValue();
                    if(!Set.of("mekanism:redstone","mekanism:carbon","mekanism:diamond","mekanism:refined_obsidian").contains(id)||amount<1)throw new IllegalStateException("Infusion chemical has no dedicated supply route");
                    suppliedChemicals.put(id,amount);
                }
            }
            boolean inscribing=recipe.type().equals("ae2:inscriber")&&raw.getClass().getMethod("getProcessType").invoke(raw).toString().equals("INSCRIBE");
            int suppliedFluids=0,suppliedItems=0;for(var ingredient:recipe.inputs()){
                if(suppliedItem!=null&&ingredient.alternatives().size()==1&&ingredient.alternatives().getFirst().material().equals(suppliedItem)&&ingredient.alternatives().getFirst().count()==1){suppliedItems++;continue;}
                if(ingredient.alternatives().stream().anyMatch(a->a.material().id().startsWith("fluidstack:")||a.material().id().startsWith("chemicalstack:"))){
                    if(Set.of("advanced_ae:reaction_chamber","industrialforegoing:dissolution").contains(recipe.type())&&!suppliedFluid.isEmpty()&&ingredient.alternatives().stream().allMatch(a->a.material().id().startsWith("fluidstack:"))&&ingredient.alternatives().stream().anyMatch(a->a.material().id().equals("fluidstack:"+suppliedFluid)&&a.count()==suppliedFluidAmount)){suppliedFluids++;continue;}
                    throw new IllegalArgumentException("Fluid/chemical input does not match an explicitly supplied reaction fluid");
                }
                if(inscribing&&ingredient.alternatives().stream().allMatch(a->a.count()==1&&a.material().id().endsWith("_press"))){
                    for(var choice:ingredient.alternatives())persistentCatalysts.add(choice.material());
                }else expectedInputs.add(ingredient);
            }
            if(suppliedItem!=null&&(suppliedItems!=1||!recipe.type().equals("mekanism:combining")))throw new IllegalStateException("The declared supplied basalt does not match the live combining recipe");
            if(Set.of("advanced_ae:reaction_chamber","industrialforegoing:dissolution").contains(recipe.type())&&(suppliedFluids!=1||recipe.outputs().getFirst().material().id().startsWith("fluidstack:")))throw new IllegalArgumentException("Only item-output reactions/dissolution with one explicitly supplied fluid are supported");
            if(!Set.of("advanced_ae:reaction_chamber","industrialforegoing:dissolution").contains(recipe.type())&&!suppliedFluid.isEmpty())throw new IllegalArgumentException("Supplied fluid is restricted to the observed reaction/dissolution recipes");
            if(expectedInputs.isEmpty())throw new IllegalArgumentException("No consumed item input remains after native inscriber catalysts");
            List<Slot> blanks=slots(menu,"BLANK_PATTERN"),encodedSlots=slots(menu,"ENCODED_PATTERN");
            if(blanks.size()!=1||encodedSlots.size()!=1)throw new IllegalStateException("Unexpected native pattern slot mapping");
            blank=blanks.getFirst();encoded=encodedSlots.getFirst();
            if(!WorldView.material(blank.getItem()).id().equals("ae2:blank_pattern")||!encoded.getItem().isEmpty())throw new IllegalStateException("Load a blank pattern and leave encoded output empty");
            blankBefore=blank.getItem().getCount();
            Object mode=api("appeng.parts.encoding.EncodingMode").getField("PROCESSING").get(null);
            menu.getClass().getMethod("setMode",api("appeng.parts.encoding.EncodingMode")).invoke(menu,mode);
            call(menu,"clear");phase=1;wait=6;save("preparing");return false;
        }
        guard();if(++elapsed>600)throw new IllegalStateException("Processing encoding timed out; inspect the checkpoint before retrying");
        if(wait-->0)return false;
        if(phase==1){JeiBridge.transfer(recipeId,false);phase=2;wait=10;save("recipe_transferred");return false;}
        if(phase==2){if(clearPersistentCatalyst())return false;if(copyPreferredGear())return false;checkGhosts();call(menu,"encode");phase=3;wait=8;save("encode_sent_once");return false;}
        if(phase==3){
            ItemStack stack=encoded.getItem();if(stack.isEmpty())return false;
            Object details=api("appeng.api.crafting.PatternDetailsHelper").getMethod("decodePattern",ItemStack.class,net.minecraft.world.level.Level.class).invoke(null,stack,mc.level);
            if(details==null||!details.getClass().getName().equals("appeng.crafting.pattern.AEProcessingPattern"))throw new IllegalStateException("Server returned another pattern type");
            List<?> inputs=(List<?>)call(details,"getSparseInputs"),outputs=(List<?>)call(details,"getOutputs");
            Map<PlanModel.Material,Long> actualInputs=new HashMap<>();
            for(Object value:inputs)if(value!=null)actualInputs.merge(WorldView.material(keyItem(value)),((Number)call(value,"amount")).longValue(),Long::sum);
            if(!actualInputs.equals(shownInputs()))throw new IllegalStateException("Encoded processing inputs differ from the visible recipe");
            var product=recipe.outputs().getFirst();
            if(outputs.size()!=1||!WorldView.material(keyItem(outputs.getFirst())).equals(product.material())||((Number)call(outputs.getFirst(),"amount")).longValue()!=product.count())throw new IllegalStateException("Encoded processing output differs from the live recipe");
            if(blank.getItem().getCount()!=blankBefore-1||stack.getCount()!=1)throw new IllegalStateException("Blank pattern consumption differs from one");
            pattern=WorldView.material(stack);before=WorldView.count(pattern);
            Actions.click(mc.player.containerMenu.containerId,encoded.index,0,ClickType.QUICK_MOVE);phase=4;wait=6;save("collect_sent_once");return false;
        }
        if(phase==4){if(!encoded.getItem().isEmpty()||WorldView.count(pattern)!=before+1)throw new IllegalStateException("Encoded processing pattern collection was not verified");save("completed_verified");return true;}
        throw new IllegalStateException("Invalid processing encoding phase");
    }
    public void cancel(){try{save("interrupted_needs_replan");}catch(Exception ignored){}}
}
