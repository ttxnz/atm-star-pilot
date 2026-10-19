package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Reads only the native Titanium menu currently open, whose tank holders are synchronized by that menu. */
public final class TitaniumInspect implements PilotController.Job {
    public String description(){return "Read own open Titanium machine's synchronized fluid/energy menu";}
    public boolean tick(PilotController controller)throws Exception {
        var mc=Actions.mc();var menu=mc.player.containerMenu;
        if(mc.screen==null||!menu.getClass().getName().equals("com.hrznstudio.titanium.container.BasicAddonContainer"))
            throw new IllegalStateException("Open the own native Titanium machine menu first");
        Object provider=menu.getClass().getMethod("getProvider").invoke(menu);
        if(!(provider instanceof BlockEntity tile))throw new IllegalStateException("Native menu provider is not a block entity");
        var pos=tile.getBlockPos();ServerRules.checkBuild(pos);
        if(!LoadedWorld.has(pos)||mc.level.getBlockEntity(pos)!=tile)throw new IllegalStateException("Open menu tile is no longer the observed loaded tile");
        Object multi=provider.getClass().getMethod("getMultiTankComponent").invoke(provider);
        JsonArray tanks=new JsonArray();
        for(Object value:multi==null?Collections.emptySet():(Set<?>)multi.getClass().getMethod("getTanks").invoke(multi)) {
            FluidTank tank=(FluidTank)value;var fluid=tank.getFluid();
            JsonObject view=Jsons.obj("name",value.getClass().getMethod("getName").invoke(value),
                "id",BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString(),"amount",fluid.getAmount(),"capacity",tank.getCapacity(),
                "type",value.getClass().getMethod("getTankType").invoke(value).toString());
            try{Map<?,?> modes=(Map<?,?>)value.getClass().getMethod("getFacingModes").invoke(value);JsonObject faces=new JsonObject();
                for(var entry:modes.entrySet())faces.addProperty(entry.getKey().toString(),entry.getValue().toString());view.add("faces",faces);
            }catch(NoSuchMethodException ignored){}
            tanks.add(view);
        }
        JsonObject result=Jsons.obj("observedAt",Instant.now().toString(),"dimension",mc.level.dimension().location().toString(),
            "menuId",menu.containerId,"class",provider.getClass().getName(),"x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),"tanks",tanks,
            "note","Only the currently open menu's synchronized native values were read; unopened machine inventories were not inspected.");
        result.addProperty("facing",provider.getClass().getMethod("getFacingDirection").invoke(provider).toString());
        if(provider.getClass().getName().equals("com.buuz135.industrialforegoingsouls.block.tile.SoulLaserBaseBlockEntity"))
            result.addProperty("souls",(Integer)provider.getClass().getMethod("getSoulAmount").invoke(provider));
        Object inventories=provider.getClass().getMethod("getMultiInventoryComponent").invoke(provider);
        JsonArray itemInventories=new JsonArray();
        for(Object inventory:(Set<?>)inventories.getClass().getMethod("getInventoryHandlers").invoke(inventories)){
            JsonObject record=Jsons.obj("name",inventory.getClass().getMethod("getName").invoke(inventory),"slots",inventory.getClass().getMethod("getSlots").invoke(inventory));
            try{Map<?,?> modes=(Map<?,?>)inventory.getClass().getMethod("getFacingModes").invoke(inventory);JsonObject sides=new JsonObject();for(var e:modes.entrySet())sides.addProperty(e.getKey().toString(),e.getValue().toString());record.add("faces",sides);}catch(NoSuchMethodException ignored){}
            itemInventories.add(record);
        }
        result.add("itemInventories",itemInventories);
        if(provider.getClass().getName().equals("com.buuz135.industrial.block.resourceproduction.tile.FluidLaserBaseTile")){
            var depth=provider.getClass().getDeclaredField("miningDepth");depth.setAccessible(true);
            result.addProperty("miningDepth",depth.getInt(provider));
        }
        try{var shape=(net.minecraft.world.phys.shapes.VoxelShape)provider.getClass().getMethod("getWorkingArea").invoke(provider);var bounds=shape.bounds();
            result.add("workingArea",Jsons.obj("minX",bounds.minX,"minY",bounds.minY,"minZ",bounds.minZ,"maxX",bounds.maxX,"maxY",bounds.maxY,"maxZ",bounds.maxZ));
        }catch(NoSuchMethodException ignored){}
        try{IEnergyStorage energy=(IEnergyStorage)provider.getClass().getMethod("getEnergyStorage").invoke(provider);
            result.add("energy",Jsons.obj("stored",energy.getEnergyStored(),"capacity",energy.getMaxEnergyStored(),"units","FE"));
        }catch(NoSuchMethodException ignored){}
        try{Object manager=provider.getClass().getMethod("getRedstoneManager").invoke(provider);
            result.addProperty("redstoneAction",manager.getClass().getMethod("getAction").invoke(manager).toString());
        }catch(NoSuchMethodException ignored){}
        JsonObject progress=new JsonObject();
        for(Class<?> type=provider.getClass();type!=null;type=type.getSuperclass())for(var field:type.getDeclaredFields()){
            if(!field.getType().getName().equals("com.hrznstudio.titanium.component.progress.ProgressBarComponent"))continue;
            field.setAccessible(true);Object bar=field.get(provider);if(bar==null)continue;
            progress.add(field.getName(),Jsons.obj("value",bar.getClass().getMethod("getProgress").invoke(bar),
                    "max",bar.getClass().getMethod("getMaxProgress").invoke(bar)));
        }
        result.add("progress",progress);
        var crop=pos.above();result.addProperty("aboveBlock",mc.level.getBlockState(crop).toString());
        result.addProperty("aboveLight",mc.level.getRawBrightness(crop,0));
        Files.writeString(controller.directory.resolve("last-titanium-inspection.json"),Jsons.GSON.toJson(result),StandardCharsets.UTF_8);return true;
    }
}
