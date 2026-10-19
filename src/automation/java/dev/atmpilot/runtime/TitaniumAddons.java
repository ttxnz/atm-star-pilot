package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Installs carried IF/MIFA addons through the currently open native Titanium slots. */
public final class TitaniumAddons implements PilotController.Job {
    private final BlockPos position;
    private final List<PlanModel.Material> wanted=new ArrayList<>();
    private MenuTransferJob transfer;
    private int container=-1,index,wait,pending=-1;
    public TitaniumAddons(JsonObject data){
        position=Actions.pos(data);
        for(var value:data.getAsJsonArray("upgrades")){
            String id=value.getAsString();
            String component=switch(id){
                case "industrialforegoing:speed_addon_tier_2","mifa:speed_addon_3","mifa:speed_addon_4"->"Speed";
                case "industrialforegoing:processing_addon_tier_2","mifa:processing_addon_3","mifa:processing_addon_4"->"Processing";
                default->throw new IllegalArgumentException("Only observed IF/MIFA speed/processing addons are supported");
            };
            int multiplier=id.endsWith("_4")?5:id.endsWith("_3")?4:3;
            var material=new PlanModel.Material(id,"{\"titanium:augments\":{\""+component+"\":"+multiplier+".0}}");
            if(wanted.contains(material))throw new IllegalArgumentException("Duplicate addon request");
            wanted.add(material);
        }
        if(wanted.isEmpty())throw new IllegalArgumentException("Explicit carried addons are required");
    }
    public String description(){return "Install carried Titanium addons at "+position.toShortString();}
    public boolean tick(PilotController c)throws Exception{
        var mc=Actions.mc();var menu=mc.player.containerMenu;
        ServerRules.checkScreenAction();ServerRules.checkBuild(position);Actions.checkReach(position);
        if(mc.screen==null||!menu.getClass().getName().equals("com.hrznstudio.titanium.container.BasicAddonContainer")||(transfer==null&&!menu.getCarried().isEmpty()))
            throw new IllegalStateException("Open the observed own Titanium machine with an empty cursor");
        Object provider=menu.getClass().getMethod("getProvider").invoke(menu);
        if(!(provider instanceof BlockEntity tile)||!tile.getBlockPos().equals(position)||!LoadedWorld.has(position)||mc.level.getBlockEntity(position)!=tile)
            throw new IllegalStateException("Titanium menu is not the requested loaded own machine");
        if(container<0)container=menu.containerId;
        if(container!=menu.containerId)throw new IllegalStateException("Titanium addon menu changed");
        for(int n=0;n<4;n++)if(menu.getSlot(n).container==mc.player.getInventory()
                ||!menu.getSlot(n).getClass().getName().equals("com.hrznstudio.titanium.container.addon.UpdatableSlotItemHandler"))
            throw new IllegalStateException("Native Titanium upgrade slot mapping changed");
        if(wait-->0)return false;
        if(transfer!=null){if(transfer.tick(c)){transfer=null;wait=6;}return false;}
        if(pending>=0){
            if(!WorldView.material(menu.getSlot(pending).getItem()).equals(wanted.get(index))||menu.getSlot(pending).getItem().getCount()!=1)
                throw new IllegalStateException("Installed addon was not synchronized; no transfer was repeated");
            pending=-1;index++;
        }
        while(index<wanted.size()){
            var material=wanted.get(index);boolean present=false;
            for(int n=0;n<4;n++)if(WorldView.material(menu.getSlot(n).getItem()).equals(material)){present=true;break;}
            if(present){index++;continue;}
            int source=-1,target=-1;
            for(var slot:menu.slots)if(WorldView.material(slot.getItem()).equals(material)
                    &&(slot.container==mc.player.getInventory()||slot.getClass().getName().equals("com.hrznstudio.titanium.container.impl.DisableableSlot"))){source=slot.index;break;}
            if(source<0)throw new IllegalStateException("Carried native addon is missing: "+material.id());
            for(int n=0;n<4;n++)if(menu.getSlot(n).getItem().isEmpty()&&menu.getSlot(n).mayPlace(menu.getSlot(source).getItem())){target=n;break;}
            if(target<0)throw new IllegalStateException("No empty native addon slot accepts the carried upgrade");
            pending=target;transfer=new MenuTransferJob(container,source,target,1);return false;
        }
        JsonArray installed=new JsonArray();for(int n=0;n<4;n++)installed.add(WorldView.stack(menu.getSlot(n).getItem()));
        Files.writeString(c.directory.resolve("last-titanium-addons.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"state","completed_verified","x",position.getX(),"y",position.getY(),"z",position.getZ(),"installed",installed)),StandardCharsets.UTF_8);
        return true;
    }
    public void cancel(){if(transfer!=null)transfer.cancel();}
}
