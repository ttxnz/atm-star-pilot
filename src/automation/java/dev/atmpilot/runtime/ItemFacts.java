package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.registries.BuiltInRegistries;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Native metadata of actual visible stacks; never constructs stock or changes components. */
public final class ItemFacts implements PilotController.Job {
    private final String item;
    public ItemFacts(JsonObject data){item=Jsons.str(data,"item","");if(item.isBlank())throw new IllegalArgumentException("An observed item ID is required");}
    public String description(){return "Read native durability of actual visible "+item;}
    private void add(JsonArray rows,ItemStack stack,String source,int index){
        if(stack.isEmpty()||!BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item))return;
        JsonObject row=Jsons.obj("source",source,"slot",index,"stack",WorldView.stack(stack),"damageable",stack.isDamageableItem(),"damage",stack.getDamageValue(),"maxDamage",stack.getMaxDamage(),"maxStackSize",stack.getMaxStackSize());
        if(stack.getItem() instanceof BlockItem placed){
            var block=placed.getBlock();row.add("block",Jsons.obj("id",BuiltInRegistries.BLOCK.getKey(block).toString(),"witherImmune",block.defaultBlockState().is(BlockTags.WITHER_IMMUNE),"explosionResistance",block.getExplosionResistance()));
        }
        rows.add(row);
    }
    public boolean tick(PilotController controller)throws Exception{
        var mc=Actions.mc();JsonArray rows=new JsonArray();var inventory=mc.player.getInventory();
        for(int i=0;i<inventory.getContainerSize();i++)add(rows,inventory.getItem(i),"player",i);
        for(var slot:mc.player.containerMenu.slots)if(slot.container!=inventory)add(rows,slot.getItem(),"open_menu",slot.index);
        if(rows.isEmpty())throw new IllegalStateException("No actual visible stack matches this item");
        Files.writeString(controller.directory.resolve("last-item-facts.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"item",item,"stacks",rows,"note","Native values of observed stacks only; reusable recipe durability may differ from ordinary damage rules.")),StandardCharsets.UTF_8);return true;
    }
}
