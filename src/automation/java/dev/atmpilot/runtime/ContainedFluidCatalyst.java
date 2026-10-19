package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Drops one observed JDT catalyst through the ordinary player drop action into a closed own cell. */
public final class ContainedFluidCatalyst implements PilotController.Job {
    private final BlockPos position;
    private final String item,input,output;
    private boolean sent; private int elapsed,aimTicks; private long before;
    public ContainedFluidCatalyst(JsonObject data){
        position=Actions.pos(data);item=Jsons.str(data,"item","");
        if(item.equals("justdirethings:polymorphic_catalyst")){input="minecraft:water";output="justdirethings:polymorphic_fluid_source";}
        else if(item.equals("justdirethings:portal_fluid_catalyst")){input="justdirethings:polymorphic_fluid_source";output="justdirethings:unstable_portal_fluid_source";}
        else throw new IllegalArgumentException("Only the two observed JDT fluid catalysts are supported");
    }
    private String fluid(){return BuiltInRegistries.FLUID.getKey(Actions.mc().level.getFluidState(position).getType()).toString();}
    private void save(String state)throws Exception{Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-contained-fluid-catalyst.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"state",state,"x",position.getX(),"y",position.getY(),"z",position.getZ(),"item",item,"input",input,"output",output,"dropSentOnce",sent,"before",before,"now",WorldView.count(new PlanModel.Material(item,"")),"actualFluid",fluid())),StandardCharsets.UTF_8);}
    public String description(){return "Drop one catalyst normally into the observed contained own fluid cell";}
    public boolean tick(PilotController controller)throws Exception{
        var mc=Actions.mc();ServerRules.checkBuild(position);ServerRules.checkItemUse();
        if(mc.screen!=null||!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Close the menu with an empty cursor before dropping a catalyst");
        for(Direction side:Direction.values())if(side!=Direction.UP){BlockPos wall=position.relative(side);ServerRules.checkBuild(wall);if(!LoadedWorld.has(wall)||!mc.level.getBlockState(wall).isFaceSturdy(mc.level,wall,side.getOpposite()))throw new IllegalStateException("The fluid cell is not fully contained");}
        if(!sent){
            if(!LoadedWorld.has(position)||!fluid().equals(input)||!mc.level.getFluidState(position).isSource())throw new IllegalStateException("Observed source fluid differs from the catalyst input");
            var expected=BuiltInRegistries.FLUID.get(net.minecraft.resources.ResourceLocation.parse(output));
            if(expected.getFluidType().isVaporizedOnPlacement(mc.level,position,new net.neoforged.neoforge.fluids.FluidStack(expected,1000)))throw new IllegalStateException("The native output fluid would vaporize in this biome; retain the catalyst");
            if(!WorldView.material(mc.player.getMainHandItem()).equals(new PlanModel.Material(item,"")))throw new IllegalStateException("Select the exact carried catalyst first");
            double dx=mc.player.getX()-(position.getX()+.5),dz=mc.player.getZ()-(position.getZ()+.5),dy=mc.player.getY()-position.getY();
            if(dx*dx+dz*dz>.09||dy<1||dy>3)throw new IllegalStateException("Hover centrally one to three blocks above the contained fluid");
            Actions.look(mc.player.getYRot(),90);
            // The server uses its last received look when creating the dropped item.
            // Allow ordinary movement packets to synchronize the downward aim first.
            if(++aimTicks<6)return false;
            before=WorldView.count(new PlanModel.Material(item,""));save("drop_intent");mc.player.drop(false);sent=true;save("drop_sent_once");return false;
        }
        if(fluid().equals(output)&&mc.level.getFluidState(position).isSource()&&WorldView.count(new PlanModel.Material(item,""))==before-1){save("completed_verified");return true;}
        if(++elapsed>240){save("stopped_needs_inspection");throw new IllegalStateException("Catalyst was dropped once but fluid conversion is unconfirmed; do not repeat the drop");}
        return false;
    }
    public void cancel(){Actions.releaseControls();}
}
