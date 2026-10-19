package dev.atmpilot.runtime;

import com.google.gson.JsonObject;
import dev.atmpilot.*;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Collect only a manually staged, server-confirmed AE grid recipe through normal craft clicks. */
public final class AeGridTake implements PilotController.Job {
    private final PlanModel.Material item;private final int count;private long target;private int menu=-1,wait,ticks;
    public AeGridTake(JsonObject d){item=new PlanModel.Material(Jsons.str(d,"item",""),Jsons.str(d,"components",""));count=Jsons.integer(d,"count",1);if(count<1||count>64||item.id().isBlank())throw new IllegalArgumentException("One explicit bounded output is required");}
    public String description(){return "Collect the observed native AE grid output "+item.id();}
    public boolean tick(PilotController c)throws Exception{
        var mc=Actions.mc();var m=mc.player.containerMenu;
        if(!m.getClass().getName().equals("appeng.menu.me.items.CraftingTermMenu"))throw new IllegalStateException("Open the own physical AE crafting terminal");
        Object host=m.getClass().getMethod("getHost").invoke(m);BlockEntity tile=(BlockEntity)host.getClass().getMethod("getBlockEntity").invoke(host);ServerRules.checkBuild(tile.getBlockPos());Actions.checkReach(tile.getBlockPos());
        if(menu<0){menu=m.containerId;target=CraftOutput.count(item)+count;}
        if(m.containerId!=menu||++ticks>240)throw new IllegalStateException("Grid output changed or timed out; do not repeat inputs");
        if(wait-->0)return false;
        var carried=m.getCarried();
        if(!carried.isEmpty()){
            if(!CraftOutput.matches(item,WorldView.material(carried)))throw new IllegalStateException("Unexpected crafting cursor; retained");
            for(var slot:m.slots)if(slot.container==mc.player.getInventory()&&slot.mayPlace(carried)&&(slot.getItem().isEmpty()||net.minecraft.world.item.ItemStack.isSameItemSameComponents(slot.getItem(),carried))&&slot.getMaxStackSize(carried)-slot.getItem().getCount()>=carried.getCount()){
                Actions.click(menu,slot.index,0,ClickType.PICKUP);wait=6;return false;
            }
            throw new IllegalStateException("No room for staged output");
        }
        long now=CraftOutput.count(item);if(now==target)return true;if(now>target)throw new IllegalStateException("Output exceeded target; inspect extra output");
        int index=CraftingMenus.resultIndex(m);var output=m.getSlot(index).getItem();
        if(output.isEmpty()||!CraftOutput.matches(item,WorldView.material(output)))throw new IllegalStateException("Native server result differs from the staged item");
        CraftingBatches.takeResult(m,index,target-now);wait=8;return false;
    }
}
