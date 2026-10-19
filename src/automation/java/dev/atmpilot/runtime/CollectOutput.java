package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.world.inventory.ClickType;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Collects a previously staged batch from explicitly observed own machine output slots. Never supplies inputs. */
public final class CollectOutput implements PilotController.Job {
    private final PlanModel.Material material;private final int requested,timeout;private final List<Integer> slots=new ArrayList<>();
    private int menuId=-1,elapsed,wait;private long baseline,target,clickBefore;private boolean clicked;
    public CollectOutput(JsonObject data){material=new PlanModel.Material(Jsons.str(data,"item",""),Jsons.str(data,"components",""));requested=Jsons.integer(data,"count",1);timeout=Jsons.integer(data,"timeoutTicks",12000);
        if(requested<1||timeout<20||timeout>72000||!data.has("slots"))throw new IllegalArgumentException("Invalid output collection request");
        for(var value:data.getAsJsonArray("slots"))slots.add(value.getAsInt());if(slots.isEmpty()||slots.size()>64||new HashSet<>(slots).size()!=slots.size())throw new IllegalArgumentException("Observe 1..64 unique output slots");}
    public String description(){return "Collect staged batch "+requested+" "+material.id();}
    private void report(PilotController c,String state)throws Exception {Files.writeString(c.directory.resolve("last-output-collection.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"state",state,
        "material",material,"requested",requested,"inventoryBefore",baseline,"targetInventory",target,"inventoryNow",WorldView.count(material),"menuId",menuId,"slots",slots,"observedTicks",elapsed)),StandardCharsets.UTF_8);}
    public boolean tick(PilotController c)throws Exception {
        ServerRules.checkScreenAction();var mc=Actions.mc();var menu=mc.player.containerMenu;if(mc.screen==null||menu.containerId==0)throw new IllegalStateException("An own machine menu must remain open");
        if(menuId<0){menuId=menu.containerId;baseline=WorldView.count(material);target=baseline+requested;
            for(int index:slots){if(index<0||index>=menu.slots.size())throw new IllegalArgumentException("Output slot outside the observed menu");var slot=menu.getSlot(index);
                if(slot.container==mc.player.getInventory()||(slot instanceof SlotItemHandler wrapped&&wrapped.getItemHandler() instanceof InvWrapper inv&&inv.getInv()==mc.player.getInventory()))throw new IllegalStateException("Output mapping must not point at player inventory");}
            report(c,"waiting_staged_batch");}
        if(menu.containerId!=menuId||!menu.getCarried().isEmpty())throw new IllegalStateException("Menu or cursor changed during output collection");
        if(++elapsed>timeout){report(c,"timed_out_needs_replan");throw new IllegalStateException("Staged machine output timed out; inputs were not repeated");}if(wait-->0)return false;
        long now=WorldView.count(material);if(clicked){if(now<=clickBefore)throw new IllegalStateException("Output pickup was not confirmed; inspect inventory space and retained output");clicked=false;}
        if(now>target)throw new IllegalStateException("Output quantity exceeded the staged batch target; inspect and account for the extra items");
        if(now==target){report(c,"completed_verified");return true;}
        for(int index:slots){var stack=menu.getSlot(index).getItem();if(stack.isEmpty())continue;if(!WorldView.material(stack).equals(material))throw new IllegalStateException("Observed output slot contains another material");
            if(stack.getCount()>target-now)throw new IllegalStateException("Observed output exceeds this batch; inspect before collection");
            clickBefore=now;Actions.click(menuId,index,0,ClickType.QUICK_MOVE);clicked=true;wait=5;return false;}
        wait=1;return false;
    }
    public void cancel(){Actions.releaseControls();if(menuId>=0)try{report(PilotMod.CONTROLLER,"interrupted_needs_replan");}catch(Exception ignored){}}
}
