package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Reads the currently open own Mek machine's synchronized frequency and side settings. */
public final class MekInspect implements PilotController.Job {
    private static Object call(Object value,String method)throws Exception{return value.getClass().getMethod(method).invoke(value);}
    private static void widgets(Object o,JsonArray out,Set<Object> seen){
        if(o==null||!seen.add(o)||out.size()>500)return;
        if(o instanceof AbstractWidget w)out.add(Jsons.obj("class",w.getClass().getName(),"x",w.getX(),"y",w.getY(),"width",w.getWidth(),"height",w.getHeight(),"active",w.active,"visible",w.visible,"text",w.getMessage().getString()));
        if(o instanceof ContainerEventHandler c)for(Object child:c.children())widgets(child,out,seen);
    }
    public String description(){return "Read own native Mek frequency and side configuration";}
    public boolean tick(PilotController c)throws Exception{
        var mc=Actions.mc();Object menu=mc.player.containerMenu;Object tile=call(menu,"getTileEntity");BlockPos p=((BlockEntity)tile).getBlockPos();ServerRules.checkBuild(p);Actions.checkReach(p);
        JsonObject report=Jsons.obj("observedAt",Instant.now().toString(),"dimension",mc.level.dimension().location().toString(),"x",p.getX(),"y",p.getY(),"z",p.getZ(),"class",tile.getClass().getName(),"menuId",mc.player.containerMenu.containerId);
        net.minecraft.core.Direction facing=null;
        for(var entry:mc.level.getBlockState(p).getValues().entrySet())if(entry.getKey().getName().equals("facing")&&entry.getValue() instanceof net.minecraft.core.Direction d)facing=d;
        if(facing!=null)report.addProperty("facing",facing.getName());
        if(tile.getClass().getName().equals("com.jerry.meklg.common.tile.generator.TileEntityLargeHeatGenerator")){
            JsonObject generation=Jsons.obj("productionJPerTick",call(tile,"getProductionRate"),"maxOutputJPerTick",call(tile,"getMaxOutput"));
            Object upgrades=call(tile,"getComponent");JsonArray supported=new JsonArray();
            for(Object type:(Set<?>)call(upgrades,"getSupportedTypes"))supported.add(type.toString());generation.add("supportedUpgrades",supported);
            Object energy=call(tile,"getEnergyContainer");generation.addProperty("energyJ",(Number)call(energy,"getEnergy"));generation.addProperty("maxEnergyJ",(Number)call(energy,"getMaxEnergy"));
            Object tank=tile.getClass().getField("lavaTank").get(tile);Object stack=call(tank,"getFluid");
            generation.add("fuel",Jsons.obj("id",net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey((net.minecraft.world.level.material.Fluid)call(stack,"getFluid")).toString(),"amount",call(stack,"getAmount"),"capacity",call(tank,"getCapacity")));
            report.add("generation",generation);Files.writeString(c.directory.resolve("last-mek-inspection.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);return true;
        }
        Object config=call(tile,"getConfig");JsonObject configs=new JsonObject();Class<?> trans=Class.forName("mekanism.common.lib.transmitter.TransmissionType");
        for(Object t:(List<?>)call(config,"getTransmissions")){
            Object info=config.getClass().getMethod("getConfig",trans).invoke(config,t);JsonObject row=new JsonObject();row.addProperty("ejecting",(Boolean)call(info,"isEjecting"));JsonObject sides=new JsonObject(),worldSides=new JsonObject();
            for(Object value:(Set<?>)call(info,"getSideConfig")){var e=(Map.Entry<?,?>)value;sides.addProperty(e.getKey().toString(),e.getValue().toString());
                if(facing!=null){var direction=(net.minecraft.core.Direction)e.getKey().getClass().getMethod("getDirection",net.minecraft.core.Direction.class).invoke(e.getKey(),facing);worldSides.addProperty(direction.getName(),e.getValue().toString());}}
            row.add("sides",sides);row.add("worldSides",worldSides);configs.add(t.toString(),row);
        }
        report.add("config",configs);
        JsonObject tanks=new JsonObject();
        for(var field:tile.getClass().getFields()){
            if(!field.getName().endsWith("Tank"))continue;Object tank=field.get(tile);if(tank==null)continue;
            try{Object stack=call(tank,"getStack");tanks.add(field.getName(),Jsons.obj("id",call(stack,"getTypeRegistryName").toString(),"amount",call(stack,"getAmount"),"capacity",call(tank,"getCapacity")));}
            catch(NoSuchMethodException ignored){try{var stack=(net.neoforged.neoforge.fluids.FluidStack)call(tank,"getFluid");tanks.add(field.getName(),Jsons.obj("id",net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString(),"amount",stack.getAmount(),"capacity",call(tank,"getCapacity")));}catch(NoSuchMethodException absent){}}
        }
        report.add("tanks",tanks);
        try{Object energy=call(tile,"getEnergyContainer");report.add("energy",Jsons.obj("stored",call(energy,"getEnergy"),"capacity",call(energy,"getMaxEnergy")));}catch(NoSuchMethodException ignored){}
        try{report.addProperty("mode",(Boolean)call(tile,"getMode"));}catch(NoSuchMethodException ignored){}
        if(tile.getClass().getName().equals("mekanism.common.tile.TileEntityQuantumEntangloporter")){
            Object f=call(tile,"getFreq");if(f!=null){JsonObject freq=Jsons.obj("name",call(f,"getName"),"security",call(f,"getSecurity").toString(),"valid",call(f,"isValid"));Object energy=f.getClass().getField("storedEnergy").get(f);freq.addProperty("energy",(Number)call(energy,"getEnergy"));freq.addProperty("maxEnergy",(Number)call(energy,"getMaxEnergy"));report.add("frequency",freq);}
        }
        JsonArray gui=new JsonArray();Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());widgets(mc.screen,gui,seen);
        if(mc.screen!=null)try{Object windows=call(mc.screen,"getWindows");if(windows instanceof Iterable<?> list)for(Object w:list)widgets(w,gui,seen);}catch(NoSuchMethodException ignored){}
        report.add("widgets",gui);Files.writeString(c.directory.resolve("last-mek-inspection.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);return true;
    }
}
