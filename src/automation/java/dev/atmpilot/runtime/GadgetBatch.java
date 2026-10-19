package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.level.ClipContext;
import net.neoforged.neoforge.capabilities.Capabilities;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Native gadget GUI settings and one ordinary item use, bounded by its actual preview. */
public final class GadgetBatch implements PilotController.Job {
    private final JsonObject data; private final String operation; private final BlockPos target;
    private int phase,ticks; private List<BlockPos> expected=List.of(); private BlockState material;
    public GadgetBatch(JsonObject d){data=d;operation=Jsons.str(d,"operation","inspect");target=d.has("x")?Actions.pos(d):null;}
    private static Class<?> type(String name)throws Exception{return Class.forName("com.direwolf20.buildinggadgets2."+name,true,PilotJobs.class.getClassLoader());}
    private static Object read(String method,ItemStack stack)throws Exception{return type("util.GadgetNBT").getMethod(method,ItemStack.class).invoke(null,stack);}
    private ItemStack held(){var stack=Actions.mc().player.getMainHandItem();if(!BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("buildinggadgets2:gadget_building"))throw new IllegalStateException("Select the real building gadget");return stack;}
    private void report(String state)throws Exception{ItemStack stack=held();var energy=stack.getCapability(Capabilities.EnergyStorage.ITEM);JsonArray positions=new JsonArray();for(var p:expected)positions.add(Jsons.obj("x",p.getX(),"y",p.getY(),"z",p.getZ()));Object mode=read("getMode",stack);Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-gadget-batch.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"state",state,"mode",mode.getClass().getMethod("getId").invoke(mode).toString(),"range",read("getToolRange",stack),"block",read("getGadgetBlockState",stack).toString(),"energy",energy==null?0:energy.getEnergyStored(),"preview",positions)));}
    private BlockHitResult ray(){var mc=Actions.mc();Vec3 point=Vec3.atCenterOf(target).add(Vec3.atLowerCornerOf(Direction.valueOf(Jsons.str(data,"face","UP")).getNormal()).scale(.499));Actions.lookAt(point);return mc.level.clip(new ClipContext(mc.player.getEyePosition(),point,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));}
    private List<BlockPos> preview()throws Exception{
        var mc=Actions.mc();ItemStack stack=held();BlockHitResult hit=ray();Direction face=Direction.valueOf(Jsons.str(data,"face","UP"));
        if(hit.getType()!=HitResult.Type.BLOCK||!hit.getBlockPos().equals(target)||hit.getDirection()!=face)throw new IllegalStateException("Gadget ray does not match the observed block face");
        if(read("getBoundPos",stack)!=null)throw new IllegalStateException("Unbind external inventories before bounded building");
        BlockPos anchor=(BlockPos)read("getAnchorPos",stack),none=(BlockPos)type("util.GadgetNBT").getField("nullPos").get(null);
        if(!anchor.equals(none))throw new IllegalStateException("Clear gadget anchor before bounded building");
        Object mode=read("getMode",stack);if(!mode.getClass().getMethod("getId").invoke(mode).toString().equals("buildinggadgets2:horizontal_wall"))throw new IllegalStateException("Choose horizontal wall mode");
        material=(BlockState)read("getGadgetBlockState",stack);String id=BuiltInRegistries.BLOCK.getKey(material.getBlock()).toString();
        if(!id.equals(Jsons.str(data,"block",""))||!Set.of("minecraft:cherry_planks","minecraft:glass","minecraft:stone").contains(id))throw new IllegalStateException("Select the observed plain floor material");
        List<?> rows=(List<?>)type("util.modes.BaseMode").getMethod("collect",Direction.class,Player.class,BlockPos.class,BlockState.class).invoke(mode,face,mc.player,target,material);
        List<BlockPos> result=new ArrayList<>();JsonObject b=data.getAsJsonObject("bounds");if(b==null)throw new IllegalArgumentException("Explicit surveyed floor bounds required");
        for(Object row:rows){BlockPos p=target.offset((BlockPos)row.getClass().getField("pos").get(row));ServerRules.checkBuild(p);if(!LoadedWorld.has(p)||p.getX()<b.get("minX").getAsInt()||p.getX()>b.get("maxX").getAsInt()||p.getY()!=b.get("y").getAsInt()||p.getZ()<b.get("minZ").getAsInt()||p.getZ()>b.get("maxZ").getAsInt()||!mc.level.getBlockState(p).isAir()||!mc.level.getFluidState(p).isEmpty())throw new IllegalStateException("Gadget preview leaves the surveyed empty floor: "+p);result.add(p);}
        if(result.size()>225)throw new IllegalStateException("Preview exceeds one native 15x15 operation");return result;
    }
    public String description(){return "Native bounded building gadget: "+operation;}
    public boolean tick(PilotController controller)throws Exception{
        var mc=Actions.mc();ItemStack stack=held();if(!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Clear cursor before gadget control");
        if(operation.equals("inspect")){report("inspected");return true;}
        if(operation.equals("configure")){
            if(phase++==0){Actions.closeMenu();int range=Jsons.integer(data,"range",9);if(range<1||range>15||range%2==0)throw new IllegalArgumentException("Use an odd native range from 1 to 15");
                Screen screen=(Screen)type("client.screen.ModeRadialMenu").getConstructor(ItemStack.class).newInstance(stack);mc.setScreen(screen);
                var field=screen.getClass().getDeclaredField("arrayOfModes");field.setAccessible(true);List<?> modes=(List<?>)field.get(screen);int selected=-1;for(int i=0;i<modes.size();i++)if(modes.get(i).getClass().getMethod("getId").invoke(modes.get(i)).toString().equals("buildinggadgets2:horizontal_wall"))selected=i;
                if(selected<0)throw new IllegalStateException("Native menu has no horizontal wall mode");var selectedField=screen.getClass().getDeclaredField("slotSelected");selectedField.setAccessible(true);selectedField.setInt(screen,selected);
                var change=screen.getClass().getDeclaredMethod("changeMode");change.setAccessible(true);change.invoke(screen);var resize=screen.getClass().getDeclaredMethod("sendRangeUpdate",int.class);resize.setAccessible(true);resize.invoke(screen,range);mc.setScreen(null);return false;}
            if(++ticks<10)return false;if(((Number)read("getToolRange",stack)).intValue()!=Jsons.integer(data,"range",9)){if(ticks<100)return false;throw new IllegalStateException("Native gadget range did not synchronize");}report("configured");return true;
        }
        if(target==null||mc.screen!=null)throw new IllegalStateException("Close menus and specify a visible gadget target");ServerRules.checkBuild(target);Actions.checkReach(target);
        if(operation.equals("select")){
            if(phase==0){ray();SneakControl.hold();if(++ticks<8)return false;mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);phase=1;ticks=0;return false;}
            if(++ticks<10)return false;Actions.releaseControls();if(!BuiltInRegistries.BLOCK.getKey(((BlockState)read("getGadgetBlockState",stack)).getBlock()).toString().equals(Jsons.str(data,"block","")))throw new IllegalStateException("Native gadget block selection failed");report("selected");return true;
        }
        if(!Set.of("preview","build").contains(operation))throw new IllegalArgumentException("Unknown gadget operation");
        if(phase==0){Actions.releaseControls();expected=preview();report("previewed");if(operation.equals("preview")||expected.isEmpty())return true;if(++ticks<8)return false;
            var energy=stack.getCapability(Capabilities.EnergyStorage.ITEM);int cost=((Number)stack.getItem().getClass().getMethod("getEnergyCost").invoke(stack.getItem())).intValue();if(energy==null||energy.getEnergyStored()<cost*expected.size())throw new IllegalStateException("Charge the actual gadget before building");if(WorldView.count(PlanModel.Material.item(Jsons.str(data,"block","")))<expected.size())throw new IllegalStateException("Carry the preview's actual materials");mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);phase=1;ticks=0;report("use_sent_once");return false;}
        if(++ticks<10)return false;boolean complete=true;for(BlockPos p:expected)if(!mc.level.getBlockState(p).equals(material))complete=false;if(complete){report("completed_verified");return true;}if(ticks>400){report("incomplete_no_retry");throw new IllegalStateException("Native gadget build incomplete; inspect before another use");}return false;
    }
    public void cancel(){Actions.releaseControls();}
}
