package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Native factory sorting and ordinary slot transfers. Every item is staged once; no refill after uncertainty. */
public final class MekBatch implements PilotController.Job {
    private final JsonObject data;private final String recipeId;private final int requested,timeout;
    private AbstractContainerMenu menu;private Object tile,chemicalIngredient;private PlanModel.Material input,output;
    private final List<Integer> inputs=new ArrayList<>(),outputs=new ArrayList<>();
    private long totalInput,target,delivered,baseline,peakUsage;private int fuelSlot=-1,phase,wait,elapsed,transferAmount;private boolean sortSent,fuelSent,collecting,fuelTransfer;
    private long collectionBefore;private MenuTransferJob transfer;
    public MekBatch(JsonObject data){
        this.data=data.deepCopy();recipeId=Jsons.str(data,"recipe","");requested=Jsons.integer(data,"count",1);timeout=Jsons.integer(data,"timeoutTicks",12000);
        if(requested<1||requested>1_000_000||timeout<40||timeout>72000)throw new IllegalArgumentException("Invalid factory count/timeout");
    }
    public String description(){return "Native parallel Mek factory batch: "+requested+" via "+recipeId+" (phase "+phase+")";}
    private static Class<?> api(String name)throws ClassNotFoundException{return Class.forName(name,true,PilotJobs.class.getClassLoader());}
    private static Object call(Object target,String method)throws ReflectiveOperationException{return target.getClass().getMethod(method).invoke(target);}
    private void report(String state)throws Exception{
        var pos=(BlockPos)call(tile,"getBlockPos");JsonObject result=Jsons.obj("observedAt",Instant.now().toString(),"recipe",recipeId,"state",state,
            "dimension",Actions.mc().level.dimension().location().toString(),"x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),
            "input",input,"output",output,"requested",requested,"expectedOutput",target-baseline,"inputToStage",totalInput,"inputDelivered",delivered,
            "inventoryBefore",baseline,"inventoryNow",WorldView.count(output),"parallelProcesses",inputs.size(),"peakNativeEnergyUsage",peakUsage,"menuId",menu.containerId);
        Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-mek-batch.json"),Jsons.GSON.toJson(result),StandardCharsets.UTF_8);
    }
    private int source(PlanModel.Material material){
        for(int i=0;i<menu.slots.size();i++){var slot=menu.getSlot(i);if(slot.container==Actions.mc().player.getInventory()&&!slot.getItem().isEmpty()&&WorldView.material(slot.getItem()).equals(material))return i;}
        throw new IllegalStateException("Factory source is absent from the observed player inventory: "+material.id());
    }
    private void initialize()throws Exception{
        var mc=Actions.mc();menu=mc.player.containerMenu;
        if(!api("mekanism.common.inventory.container.tile.FactoryContainer").isInstance(menu))throw new IllegalStateException("Open the own Mek factory first");
        tile=call(menu,"getTileEntity");ServerRules.checkBuild((BlockPos)call(tile,"getBlockPos"));
        var entry=JeiBridge.entry(recipeId);String category=entry.recipe().type(),factory=call(tile,"getFactoryType").toString();
        if(!(category.equals("mekanism:enriching")&&factory.equalsIgnoreCase("enriching"))
            &&!(category.equals("mekanism:metallurgic_infusing")&&factory.equalsIgnoreCase("infusing"))
            &&!(category.equals("mekanism:crushing")&&factory.equalsIgnoreCase("crushing")))
            throw new IllegalStateException("This adapter requires a matching enrichment, crushing or metallurgic infusing factory");
        if(!entry.recipe().deterministic()||entry.recipe().outputs().size()!=1||!menu.getCarried().isEmpty())throw new IllegalStateException("Factory batching requires a deterministic single output and empty cursor");
        Class<?> nativeSlot=api("mekanism.common.inventory.container.slot.InventoryContainerSlot");
        for(int i=0;i<menu.slots.size();i++)if(nativeSlot.isInstance(menu.getSlot(i))){
            Object slot=nativeSlot.getMethod("getInventorySlot").invoke(menu.getSlot(i));String role=slot.getClass().getName();
            if(role.endsWith(".FactoryInputInventorySlot"))inputs.add(i);
            else if(role.endsWith(".OutputInventorySlot"))outputs.add(i);
            else if(role.endsWith(".ChemicalInventorySlot"))fuelSlot=i;
        }
        if(inputs.isEmpty()||inputs.size()!=outputs.size())throw new IllegalStateException("Unexpected native factory slot roles");
        for(int i:inputs)if(!menu.getSlot(i).getItem().isEmpty())throw new IllegalStateException("Factory already contains inputs; inspect and replan that unfinished batch");
        for(int i:outputs)if(!menu.getSlot(i).getItem().isEmpty())throw new IllegalStateException("Collect and account for existing factory outputs before a new batch");
        var product=entry.recipe().outputs().getFirst();output=product.material();baseline=WorldView.count(output);
        long batches=(requested+product.count()-1)/product.count();target=Math.addExact(baseline,Math.multiplyExact(batches,product.count()));
        List<PlanModel.Ingredient> itemInputs=entry.recipe().inputs().stream().filter(ingredient->ingredient.alternatives().stream().anyMatch(a->!a.material().id().startsWith("chemicalstack:")&&!a.material().id().startsWith("fluidstack:"))).toList();
        if(itemInputs.size()!=1)throw new IllegalStateException("This factory adapter supports one item ingredient");
        var choice=itemInputs.getFirst().alternatives().stream().filter(a->WorldView.count(a.material())>=Math.multiplyExact(batches,a.count())).findFirst().orElseThrow(()->new IllegalStateException("The complete batch input must be present before staging"));
        input=choice.material();totalInput=Math.multiplyExact(batches,choice.count());
        Object raw=entry.raw();Object recipe=raw instanceof RecipeHolder<?> holder?holder.value():raw;
        Class<?> chemicalType=api("mekanism.api.recipes.ItemStackChemicalToObjectRecipe");
        if(chemicalType.isInstance(recipe)){
            if((Boolean)chemicalType.getMethod("perTickUsage").invoke(recipe))throw new IllegalStateException("Per-tick chemical consumption requires a separate continuous-supply adapter");
            chemicalIngredient=chemicalType.getMethod("getChemicalInput").invoke(recipe);
            if(fuelSlot<0)throw new IllegalStateException("Native chemical input slot is absent");
        }
        report("initialized");
    }
    private boolean chemicalReady()throws Exception{
        if(chemicalIngredient==null)return true;
        Object tank=call(tile,"getChemicalTank"),stack=api("mekanism.api.chemical.IChemicalTank").getMethod("getStack").invoke(tank);
        Class<?> ingredientType=api("mekanism.api.recipes.ingredients.ChemicalStackIngredient"),stackType=api("mekanism.api.chemical.ChemicalStack");
        long amount=((Number)stackType.getMethod("getAmount").invoke(stack)).longValue();
        boolean matches=(Boolean)ingredientType.getMethod("testType",stackType).invoke(chemicalIngredient,stack);
        if(amount>0&&!matches)throw new IllegalStateException("Factory contains another chemical; no dumping or replacement was attempted");
        var product=JeiBridge.entry(recipeId).recipe().outputs().getFirst();long batches=(target-baseline)/product.count();
        long needed=Math.multiplyExact(batches,((Number)ingredientType.getMethod("amount").invoke(chemicalIngredient)).longValue());
        return matches&&amount>=needed;
    }
    public boolean tick(PilotController controller)throws Exception{
        var mc=Actions.mc();ServerRules.checkScreenAction();
        if(menu==null)initialize();
        if(menu!=mc.player.containerMenu)throw new IllegalStateException("Factory menu changed");
        ServerRules.checkBuild((BlockPos)call(tile,"getBlockPos"));
        if(++elapsed>timeout){report("timed_out_needs_replan");throw new IllegalStateException("Factory timed out; inspect power, remaining inputs/chemical and outputs before retrying");}
        peakUsage=Math.max(peakUsage,((Number)call(tile,"getLastUsage")).longValue());
        if(wait-->0)return false;
        if(transfer!=null){if(transfer.tick(controller)){transfer=null;if(fuelTransfer){fuelTransfer=false;fuelSent=true;}else delivered+=transferAmount;wait=4;report("staged");}return false;}
        if(phase==0){
            if(!(Boolean)call(tile,"isSorting")){
                if(sortSent){if(elapsed>100)throw new IllegalStateException("Native factory sorting did not become enabled");return false;}
                JsonObject screen=ScreenAccess.describe();JsonObject button=null;
                for(var value:screen.getAsJsonArray("widgets")){var widget=value.getAsJsonObject();if(Jsons.str(widget,"class","").equals("mekanism.client.gui.element.tab.GuiSortingTab")&&Jsons.bool(widget,"active",false)&&Jsons.bool(widget,"visible",false)){button=widget;break;}}
                if(button==null)throw new IllegalStateException("The visible native sorting button was not found");
                ScreenAccess.click(Jsons.obj("screenClass",screen.get("class").getAsString(),"x",Jsons.integer(button,"x",0)+Jsons.integer(button,"width",0)/2,"y",Jsons.integer(button,"y",0)+Jsons.integer(button,"height",0)/2,"button",0));
                sortSent=true;wait=5;return false;
            }
            phase=1;
        }
        if(phase==1){
            if(chemicalReady()){phase=2;report("supply_verified");}
            else{
                if(!data.has("reagent"))throw new IllegalStateException("The exact native chemical requirement is not supplied; provide an observed reagent batch");
                if(!fuelSent){
                    JsonObject reagent=data.getAsJsonObject("reagent");int count=Jsons.integer(reagent,"count",1);
                    if(count<1||count>64||!menu.getSlot(fuelSlot).getItem().isEmpty())throw new IllegalStateException("Reagent count or existing chemical input needs inspection");
                    var material=new PlanModel.Material(Jsons.str(reagent,"item",""),Jsons.str(reagent,"components",""));
                    transfer=new MenuTransferJob(menu.containerId,source(material),fuelSlot,count);fuelTransfer=true;return false;
                }
                if(elapsed>200)throw new IllegalStateException("The staged reagent did not satisfy the native chemical requirement; no more was added");return false;
            }
        }
        if(collecting){if(WorldView.count(output)<=collectionBefore)return false;collecting=false;report("output_collected");}
        for(int i:outputs){ItemStack stack=menu.getSlot(i).getItem();if(stack.isEmpty())continue;
            if(!WorldView.material(stack).equals(output))throw new IllegalStateException("Factory produced another material; inspect the actual recipe");
            collectionBefore=WorldView.count(output);Actions.click(menu.containerId,i,0,ClickType.QUICK_MOVE);collecting=true;wait=6;return false;
        }
        if(WorldView.count(output)>target)throw new IllegalStateException("Factory output exceeded the planned exact recipe batches");
        if(delivered==totalInput&&WorldView.count(output)==target&&menu.getCarried().isEmpty()){
            for(int i:inputs)if(!menu.getSlot(i).getItem().isEmpty())throw new IllegalStateException("Unexpected input remains after the planned output was collected");
            report("completed_verified");return true;
        }
        if(delivered<totalInput){
            int source=source(input);ItemStack sourceStack=menu.getSlot(source).getItem();
            for(int i:inputs){ItemStack current=menu.getSlot(i).getItem();
                if(!current.isEmpty()&&!WorldView.material(current).equals(input))throw new IllegalStateException("Another item appeared in a factory input");
                int capacity=menu.getSlot(i).getMaxStackSize(sourceStack)-current.getCount();if(capacity<=0)continue;
                transferAmount=(int)Math.min(64,Math.min(capacity,Math.min(totalInput-delivered,sourceStack.getCount())));
                transfer=new MenuTransferJob(menu.containerId,source,i,transferAmount);return false;
            }
        }
        wait=2;return false;
    }
    public void cancel(){if(transfer!=null)transfer.cancel();Actions.releaseControls();}
}
