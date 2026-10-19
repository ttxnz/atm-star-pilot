package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Tests the open native menu's own slot rules without inserting an item or changing configuration. */
public final class MenuProbe implements PilotController.Job {
    private final String item;public MenuProbe(JsonObject data){item=Jsons.str(data,"item","");}
    public boolean tick(PilotController c)throws Exception {
        ServerRules.checkScreenAction();var mc=Actions.mc();var menu=mc.player.containerMenu;
        if(mc.screen==null||menu.containerId==0)throw new IllegalStateException("Open the own machine menu first");
        var key=ResourceLocation.parse(item);if(!BuiltInRegistries.ITEM.containsKey(key))throw new IllegalArgumentException("Unknown probe item");
        ItemStack sample=new ItemStack(BuiltInRegistries.ITEM.get(key));JsonArray slots=new JsonArray();
        for(int i=0;i<menu.slots.size();i++){Slot slot=menu.getSlot(i);if(slot.container==mc.player.getInventory())continue;
            JsonObject value=Jsons.obj("slot",i,"item",WorldView.stack(slot.getItem()),"accepts",slot.mayPlace(sample),"limitForItem",slot.getMaxStackSize(sample));
            try{Object semantic=menu.getClass().getMethod("getSlotSemantic",Slot.class).invoke(menu,slot);if(semantic!=null)value.addProperty("semantic",semantic.getClass().getMethod("id").invoke(semantic).toString());}catch(NoSuchMethodException ignored){}
            slots.add(value);
        }
        Files.writeString(c.directory.resolve("last-menu-probe.json"),Jsons.GSON.toJson(Jsons.obj("menuId",menu.containerId,"class",menu.getClass().getName(),"probeItem",item,"slots",slots)),StandardCharsets.UTF_8);return true;
    }
}
