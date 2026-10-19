package dev.atmpilot;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;

/** Stopped-menu recovery through ordinary clicks; never drops or rewrites a stack. */
public final class CursorReturn implements java.util.function.Supplier<JsonObject> {
    private final JsonObject data;
    public CursorReturn(JsonObject data){this.data=data;}
    public JsonObject get(){return run(data);}
    public static JsonObject run(JsonObject data){
        var mc=Actions.mc();var c=PilotMod.CONTROLLER;var menu=mc.player.containerMenu;
        if(c.running())throw new IllegalStateException("Stop and inspect the current job before returning its cursor");
        if(menu.containerId!=Jsons.integer(data,"containerId",-1))throw new IllegalStateException("Cursor-return menu changed");
        var carried=menu.getCarried();
        if(carried.isEmpty()||carried.getCount()!=Jsons.integer(data,"count",-1)||!BuiltInRegistries.ITEM.getKey(carried.getItem()).toString().equals(Jsons.str(data,"item","")))throw new IllegalStateException("Observed cursor item or quantity changed");
        int index=Jsons.integer(data,"slot",-1);if(index<0||index>=menu.slots.size())throw new IllegalArgumentException("Invalid cursor-return slot");
        var target=menu.getSlot(index);
        if(Jsons.bool(data,"freeOwnAePlayerSlot",false)){
            ServerRules.checkBuild(mc.player.blockPosition());
            if(!menu.getClass().getName().equals("appeng.menu.me.items.CraftingTermMenu"))throw new IllegalStateException("Freeing a recovery slot requires the own physical AE crafting terminal");
            try{
                Object host=menu.getClass().getMethod("getHost").invoke(menu);
                var tile=(net.minecraft.world.level.block.entity.BlockEntity)host.getClass().getMethod("getBlockEntity").invoke(host);
                ServerRules.checkBuild(tile.getBlockPos());Actions.checkReach(tile.getBlockPos());
            }catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot verify the own physical AE host",e);}
            var stack=target.getItem();String source=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if(target.container!=mc.player.getInventory()||stack.isEmpty()||stack.getMaxStackSize()<2||source.contains("backpack")
                ||!source.equals(Jsons.str(data,"sourceItem",""))||stack.getCount()!=Jsons.integer(data,"sourceCount",-1))
                throw new IllegalStateException("Only an unchanged observed stackable player material may be returned to own AE to free a recovery slot");
            Actions.click(menu.containerId,index,0,ClickType.QUICK_MOVE);
            return Jsons.obj("freeSlotClickSentOnce",true,"containerId",menu.containerId,"slot",index,"needsInventoryVerification",true);
        }
        if(target.container!=mc.player.getInventory()||!target.getItem().isEmpty()||!target.mayPlace(carried)||target.getMaxStackSize(carried)<carried.getCount())throw new IllegalStateException("Cursor return requires an observed empty, compatible player-inventory slot");
        var before=WorldView.stack(carried);Actions.click(menu.containerId,index,0,ClickType.PICKUP);
        return Jsons.obj("clickSentOnce",true,"containerId",menu.containerId,"slot",index,"item",before.get("id"),"count",before.get("count"),"needsInventoryVerification",true);
    }
}
