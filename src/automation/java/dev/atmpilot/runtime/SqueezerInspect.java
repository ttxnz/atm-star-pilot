package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Reads only the native squeezer renderer's displayed item, fluid and public block state. */
public final class SqueezerInspect implements PilotController.Job {
    private final BlockPos position;private final JsonObject checks;public SqueezerInspect(JsonObject data){position=Actions.pos(data);checks=data.deepCopy();}
    public String description(){return "Inspect own squeezer displayed item and fluid";}
    public boolean tick(PilotController c)throws Exception {
        var mc=Actions.mc();ServerRules.checkBuild(position);Actions.checkReach(position);var entity=mc.level.getBlockEntity(position);
        if(entity==null||!entity.getClass().getName().equals("org.cyclops.integrateddynamics.blockentity.BlockEntitySqueezer"))throw new IllegalStateException("Expected the loaded own squeezer");
        Object inventory=entity.getClass().getMethod("getInventory").invoke(entity);ItemStack item=(ItemStack)inventory.getClass().getMethod("getItem",int.class).invoke(inventory,0);
        Object tank=entity.getClass().getMethod("getTank").invoke(entity);FluidStack fluid=(FluidStack)tank.getClass().getMethod("getFluid").invoke(tank);var state=mc.level.getBlockState(position);
        int height=Integer.parseInt(state.getValues().entrySet().stream().filter(e->e.getKey().getName().equals("height")).findFirst().orElseThrow().getValue().toString());
        if(Jsons.bool(checks,"requireOnPlatform",false)){
            double surface=position.getY()+1-(height-1)*.125;
            if(Math.hypot(mc.player.getX()-position.getX()-.5,mc.player.getZ()-position.getZ()-.5)>.2||Math.abs(mc.player.getY()-surface)>.07||!mc.player.onGround()||mc.player.getAbilities().flying)throw new IllegalStateException("Stand on the squeezer center with flight disabled before jumping or refilling");
        }
        if(Jsons.bool(checks,"expectEmpty",false)&&!item.isEmpty())throw new IllegalStateException("Squeezer still contains an unfinished input; do not refill");
        if(checks.has("expectedHeight")&&height!=checks.get("expectedHeight").getAsInt())throw new IllegalStateException("Squeezer plate did not reach the expected height");
        if(checks.has("expectedPlayerCount")&&WorldView.count(PlanModel.Material.item("minecraft:blaze_powder"))!=checks.get("expectedPlayerCount").getAsLong())throw new IllegalStateException("Squeezer input inventory differs from the accounted batch");
        if(checks.has("expectedDisplayedItem")&&(!WorldView.material(item).id().equals(checks.get("expectedDisplayedItem").getAsString())||item.getCount()!=1))throw new IllegalStateException("Squeezer displayed input was not confirmed");
        if(checks.has("lever")){BlockPos lever=Actions.pos(checks.getAsJsonObject("lever"));ServerRules.checkBuild(lever);Actions.checkReach(lever);var leverState=mc.level.getBlockState(lever);
            if(!leverState.is(net.minecraft.world.level.block.Blocks.LEVER)||leverState.getValue(net.minecraft.world.level.block.LeverBlock.POWERED)!=Jsons.bool(checks,"expectedLeverPowered",false))throw new IllegalStateException("Squeezer reset lever differs from the expected state");}
        JsonObject report=Jsons.obj("observedAt",Instant.now().toString(),"x",position.getX(),"y",position.getY(),"z",position.getZ(),"state",state.toString(),"displayedItem",WorldView.stack(item),"displayedFluid",BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString(),"amount",fluid.getAmount(),"itemHeight",entity.getClass().getMethod("getItemHeight").invoke(entity));
        Files.writeString(c.directory.resolve("last-squeezer-inspection.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);return true;
    }
    public static final class Tank implements PilotController.Job {
        public String description(){return "Read synchronized fluid in the open own Mek tank";}
        public boolean tick(PilotController c)throws Exception {
            var mc=Actions.mc();ServerRules.checkScreenAction();var menu=mc.player.containerMenu;if(mc.screen==null||menu.containerId==0)throw new IllegalStateException("Open the own Mek tank first");
            Object tile=menu.getClass().getMethod("getTileEntity").invoke(menu);if(!tile.getClass().getName().equals("mekanism.common.tile.TileEntityFluidTank"))throw new IllegalStateException("Expected the currently open Mek tank");
            var position=(BlockPos)tile.getClass().getMethod("getBlockPos").invoke(tile);ServerRules.checkBuild(position);Actions.checkReach(position);Object tank=tile.getClass().getField("fluidTank").get(tile);FluidStack fluid=(FluidStack)tank.getClass().getMethod("getFluid").invoke(tank);
            JsonObject report=Jsons.obj("observedAt",Instant.now().toString(),"menuId",menu.containerId,"x",position.getX(),"y",position.getY(),"z",position.getZ(),"fluid",BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString(),"amount",fluid.getAmount(),"capacity",tank.getClass().getMethod("getCapacity").invoke(tank),"editMode",tile.getClass().getMethod("getContainerEditMode").invoke(tile).toString());
            Files.writeString(c.directory.resolve("last-fluid-tank-inspection.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);return true;
        }
    }
}
