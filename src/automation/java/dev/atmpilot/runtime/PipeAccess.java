package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Pipez connection arrows are client-synchronized. Configuration uses the normal held wrench once. */
public final class PipeAccess {
    private static Object pipe(BlockPos pos) {
        ServerRules.checkBuild(pos);if(!LoadedWorld.has(pos))throw new IllegalStateException("Pipe chunk has not been received");
        var block=Actions.mc().level.getBlockState(pos).getBlock();String type=block.getClass().getName();
        if(!type.startsWith("de.maxhenkel.pipez.blocks.")||!type.endsWith("PipeBlock"))throw new IllegalStateException("No own Pipez pipe observed at "+pos);
        return block;
    }
    private static boolean flag(BlockPos pos,Direction side,String method)throws Exception {
        // Native plain pipes intentionally have no block entity until a connection needs extra data.
        Object block=pipe(pos);return (Boolean)block.getClass().getMethod(method,net.minecraft.world.level.LevelAccessor.class,BlockPos.class,Direction.class)
            .invoke(block,Actions.mc().level,pos,side);
    }
    private static JsonObject view(BlockPos pos)throws Exception {
        JsonObject sides=new JsonObject();for(Direction side:Direction.values())sides.add(side.getName(),Jsons.obj("extracting",flag(pos,side,"isExtracting"),"disconnected",flag(pos,side,"isDisconnected")));
        return Jsons.obj("x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),"sides",sides);
    }
    private static void save(PilotController controller,String file,JsonObject data)throws Exception {
        data.addProperty("observedAt",Instant.now().toString());Files.writeString(controller.directory.resolve(file),Jsons.GSON.toJson(data),StandardCharsets.UTF_8);
    }
    public static final class Inspect implements PilotController.Job {
        private final JsonArray positions;
        public Inspect(JsonObject data){positions=data.getAsJsonArray("positions");if(positions==null||positions.isEmpty()||positions.size()>128)throw new IllegalArgumentException("Inspect 1..128 own pipes");}
        public boolean tick(PilotController controller)throws Exception {
            JsonArray pipes=new JsonArray();for(var value:positions)pipes.add(view(Actions.pos(value.getAsJsonObject())));
            var report=Jsons.obj("pipes",pipes,"source","Native client-synchronized visible connection arrows");
            Object menu=Actions.mc().player.containerMenu;
            if(menu.getClass().getName().equals("de.maxhenkel.pipez.gui.ExtractContainer")){
                Object tile=menu.getClass().getMethod("getPipe").invoke(menu);var pos=((net.minecraft.world.level.block.entity.BlockEntity)tile).getBlockPos();ServerRules.checkBuild(pos);
                Direction side=(Direction)menu.getClass().getMethod("getSide").invoke(menu);int index=0;
                // ExtractContainer.getIndex is a selected filter index, not a pipe-type index.
                Object[] types=(Object[])tile.getClass().getMethod("getPipeTypes").invoke(tile);if(types.length!=1)throw new IllegalStateException("Open a single-type pipe for extraction inspection");
                Class<?> type=Class.forName("de.maxhenkel.pipez.blocks.tileentity.types.PipeType",true,PilotJobs.class.getClassLoader());
                Object distribution=tile.getClass().getMethod("getDistribution",Direction.class,type).invoke(tile,side,types[index]);
                report.add("extractionMenu",Jsons.obj("menuId",Actions.mc().player.containerMenu.containerId,"x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),"side",side.getName(),"index",index,"distribution",distribution.toString(),"upgrade",WorldView.stack(Actions.mc().player.containerMenu.getSlot(0).getItem())));
                if(types[0].getClass().getName().equals("de.maxhenkel.pipez.blocks.tileentity.types.ItemPipeType"))report.add("itemFilter",PipeFilterAccess.menuView(menu));
            }
            save(controller,"last-pipe-inspection.json",report);return true;
        }
    }
    public static final class Configure implements PilotController.Job {
        private final BlockPos pos,stand;private final Direction side;private final boolean expected;private PilotController.Job approach;private int phase,wait,stableShift,shiftElapsed;
        public Configure(JsonObject data){pos=Actions.pos(data);stand=data.has("stand")?Actions.pos(data.getAsJsonObject("stand")):null;side=Direction.valueOf(Jsons.str(data,"side","").toUpperCase(java.util.Locale.ROOT));expected=Jsons.bool(data,"extracting",true);}
        public String description(){return "Normal Pipez wrench: "+pos.toShortString()+" "+side+" extracting="+expected;}
        public boolean tick(PilotController controller)throws Exception {
            var mc=Actions.mc();pipe(pos);ServerRules.checkBuild(pos.relative(side));
            if(wait-->0){if(phase>=2&&phase<=4)SneakControl.hold();return false;}
            if(phase==0){
                if(flag(pos,side,"isExtracting")==expected){save(controller,"last-pipe-job.json",Jsons.obj("state","already_verified","pipe",view(pos)));return true;}
                Actions.closeMenu();BlockPos destination=stand==null?pos:stand;if(stand!=null)ServerRules.checkBuild(stand);
                boolean already=stand!=null&&mc.player.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(stand))<.75;
                approach=already?null:PilotJobs.create(Jsons.obj("type",stand==null?"walk_near":"walk","x",destination.getX(),"y",destination.getY(),"z",destination.getZ()));phase=1;
            }
            if(phase==1){
                if(approach!=null&&!approach.tick(controller))return false;Actions.releaseControls();int slot=-1;
                for(int i=0;i<36;i++)if(BuiltInRegistries.ITEM.getKey(mc.player.getInventory().getItem(i).getItem()).toString().equals("pipez:wrench")){slot=i;break;}
                if(slot<0)throw new IllegalStateException("The normal Pipez wrench must be present");
                if(slot>=9){Actions.click(mc.player.inventoryMenu.containerId,slot,8,ClickType.SWAP);slot=8;}Actions.select(slot);phase=2;
            }
            if(phase==2){SneakControl.hold();Actions.lookAt(net.minecraft.world.phys.Vec3.atCenterOf(pos).add(net.minecraft.world.phys.Vec3.atLowerCornerOf(side.getNormal()).scale(.49)));phase=3;return false;}
            if(phase==3){
                SneakControl.hold();Actions.lookAt(net.minecraft.world.phys.Vec3.atCenterOf(pos).add(net.minecraft.world.phys.Vec3.atLowerCornerOf(side.getNormal()).scale(.49)));
                stableShift=mc.player.isShiftKeyDown()?stableShift+1:0;if(++shiftElapsed>200)throw new IllegalStateException("Could not hold stable actual Shift for pipe configuration");if(stableShift<30)return false;
                if(!BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString().equals("pipez:wrench"))throw new IllegalStateException("Held wrench changed");
                save(controller,"last-pipe-job.json",Jsons.obj("state","click_intent","pipe",view(pos),"side",side.getName(),"expected",expected,"stableShiftTicks",stableShift,"actualShiftAtClick",mc.player.isShiftKeyDown()));
                Actions.useBlock(pos,side,false);phase=4;wait=40;return false;
            }
            if(flag(pos,side,"isExtracting")!=expected)throw new IllegalStateException("Pipe extraction change was not confirmed; no click was repeated");
            save(controller,"last-pipe-job.json",Jsons.obj("state","completed_verified","pipe",view(pos)));return true;
        }
        public void cancel(){if(approach!=null)approach.cancel();Actions.releaseControls();}
    }
}
