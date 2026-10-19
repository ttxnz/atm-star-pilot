package dev.atmpilot;

import com.google.gson.*;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import java.util.*;

/** Recipe-aware batch production using explicitly observed menu slot mappings. */
public final class MachineJob implements PilotController.Job {
    private final String recipeId;
    private final int requested,timeout;
    private final List<Integer> inputs=new ArrayList<>(),outputs=new ArrayList<>();
    private int container=-1,phase,inputIndex,wait;
    private long deadline,target;
    private PlanModel.Material product;
    private MenuTransferJob transfer;
    private List<PlanModel.Amount> selected;
    public MachineJob(JsonObject data) {
        recipeId=Jsons.str(data,"recipe","");requested=Jsons.integer(data,"count",1);
        timeout=Jsons.integer(data,"timeoutTicks",12_000);
        if(requested<1||requested>1_000_000||timeout<20||timeout>72_000)throw new IllegalArgumentException("Invalid machine count/timeout");
        if(!data.has("inputSlots")||!data.has("outputSlots"))throw new IllegalArgumentException("Explicit inputSlots and outputSlots are required");
        data.getAsJsonArray("inputSlots").forEach(e->inputs.add(e.getAsInt()));
        data.getAsJsonArray("outputSlots").forEach(e->outputs.add(e.getAsInt()));
        if(inputs.isEmpty()||outputs.isEmpty()||!Collections.disjoint(inputs,outputs))throw new IllegalArgumentException("Invalid machine slot mapping");
    }
    public String description(){return "Batch production "+requested+" via "+recipeId;}
    public boolean tick(PilotController controller)throws Exception {
        ServerRules.checkScreenAction();
        var mc=Actions.mc();var menu=mc.player.containerMenu;var entry=JeiBridge.entry(recipeId);
        if(container<0){
            container=menu.containerId;if(container==0)throw new IllegalStateException("Open a machine menu first");
            if(entry.recipe().inputs().size()!=inputs.size())throw new IllegalStateException("Input slot mapping does not match JEI ingredient slots");
            if(!entry.recipe().deterministic())throw new IllegalStateException("Probabilistic or ambiguous recipe needs a specific adapter");
            for(int slot:inputs)validateSlot(slot,menu.slots.size());for(int slot:outputs)validateSlot(slot,menu.slots.size());
            for(int index:inputs){var slot=menu.getSlot(index);if(slot.container==mc.player.getInventory()
                ||(slot instanceof SlotItemHandler wrapped&&wrapped.getItemHandler() instanceof InvWrapper inv&&inv.getInv()==mc.player.getInventory()))
                throw new IllegalStateException("Machine input mapping points at player inventory");}
            for(int index:outputs){var slot=menu.getSlot(index);if(slot.container==mc.player.getInventory()
                ||(slot instanceof SlotItemHandler wrapped&&wrapped.getItemHandler() instanceof InvWrapper inv&&inv.getInv()==mc.player.getInventory()))
                throw new IllegalStateException("Machine output mapping points at player inventory");}
            product=entry.recipe().outputs().getFirst().material();target=WorldView.count(product)+requested;
            deadline=controller.ticks()+timeout;
        }
        if(menu.containerId!=container)throw new IllegalStateException("Machine menu changed during production");
        if(controller.ticks()>deadline)throw new IllegalStateException("Machine timed out: check power, fluids, sidedness and recipe conditions");
        if(wait-->0)return false;
        if(transfer!=null){if(transfer.tick(controller)){transfer=null;wait=4;}return false;}
        if(WorldView.count(product)>=target&&menu.getCarried().isEmpty())return true;
        // Collect actual outputs before scheduling another batch.
        for(int slot:outputs){ItemStack stack=menu.getSlot(slot).getItem();
            if(phase==1)break;
            if(!stack.isEmpty()&&entry.recipe().outputs().stream().anyMatch(a->a.material().equals(WorldView.material(stack)))){
                Actions.click(container,slot,0,ClickType.QUICK_MOVE);phase=0;wait=6;return false;
            }
        }
        if(phase==0){
            selected=new ArrayList<>();Map<PlanModel.Material,Long> available=new HashMap<>(WorldView.inventory());
            for(int i=0;i<entry.recipe().inputs().size();i++){
                var ingredient=entry.recipe().inputs().get(i);var inSlot=menu.getSlot(inputs.get(i)).getItem();
                PlanModel.Amount choice=ingredient.alternatives().stream().filter(a->{
                    long present=inSlot.isEmpty()?0:(WorldView.material(inSlot).equals(a.material())?inSlot.getCount():0);
                    return present+available.getOrDefault(a.material(),0L)>=a.count();
                }).findFirst().orElseThrow(()->new IllegalStateException("Missing machine ingredient: "+ingredient.alternatives()));
                if(choice.material().id().startsWith("fluidstack:")||choice.material().id().startsWith("chemicalstack:"))
                    throw new IllegalStateException("Fluid/chemical provisioning requires a configured supply line");
                long present=inSlot.isEmpty()?0:(WorldView.material(inSlot).equals(choice.material())?inSlot.getCount():0);
                // Track items still to deliver, independently of a fast machine consuming its input.
                selected.add(choice.count()>present?new PlanModel.Amount(choice.material(),choice.count()-present):null);
                available.computeIfPresent(choice.material(),(k,v)->Math.max(0,v-Math.max(0,choice.count()-present)));
            }
            inputIndex=0;phase=1;
        }
        if(phase==1){
            if(inputIndex>=selected.size()){phase=2;wait=8;return false;}
            var need=selected.get(inputIndex);if(need==null){inputIndex++;return false;}
            int destination=inputs.get(inputIndex);ItemStack present=menu.getSlot(destination).getItem();
            if(!present.isEmpty()&&!WorldView.material(present).equals(need.material()))throw new IllegalStateException("Machine input contains an unexpected item");
            int source=-1;
            for(int i=0;i<menu.slots.size();i++){var slot=menu.getSlot(i);
                boolean playerSlot=slot.container==mc.player.getInventory()
                    ||(slot instanceof SlotItemHandler wrapped&&wrapped.getItemHandler() instanceof InvWrapper inv&&inv.getInv()==mc.player.getInventory());
                if(playerSlot&&!slot.getItem().isEmpty()&&WorldView.material(slot.getItem()).equals(need.material())){source=i;break;}}
            if(source<0)throw new IllegalStateException("Machine ingredient was consumed or moved before transfer: "+need.material().id());
            int capacity=menu.getSlot(destination).getMaxStackSize(menu.getSlot(source).getItem())-present.getCount();
            if(capacity<=0){wait=4;return false;}
            int amount=(int)Math.min(capacity,Math.min(64,Math.min(need.count(),menu.getSlot(source).getItem().getCount())));
            transfer=new MenuTransferJob(container,source,destination,amount);
            selected.set(inputIndex,need.count()>amount?new PlanModel.Amount(need.material(),need.count()-amount):null);return false;
        }
        // Wait for a visible result. Existing progress is never reported as completed output.
        if(phase==2){
            wait=4;
        }
        return false;
    }
    private static void validateSlot(int slot,int count){if(slot<0||slot>=count)throw new IllegalArgumentException("Machine slot outside menu");}
    public void cancel(){if(transfer!=null)transfer.cancel();}
}
