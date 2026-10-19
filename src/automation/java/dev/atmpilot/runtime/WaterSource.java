package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** A normal bucket fill at an own Sink, followed by one normal aimed bucket use in a contained cell. */
public final class WaterSource implements PilotController.Job {
    private final BlockPos pos,source,stand;private PilotController.Job approach;private int phase,wait,elapsed;private long waterBefore;private String detail="";
    private static final PlanModel.Material WATER=new PlanModel.Material("minecraft:water_bucket",""),BUCKET=new PlanModel.Material("minecraft:bucket","");
    private static BlockHitResult ray(boolean empty){var mc=Actions.mc();Vec3 eye=mc.player.getEyePosition();return mc.level.clip(new ClipContext(eye,
        eye.add(mc.player.getViewVector(1).scale(mc.player.blockInteractionRange())),ClipContext.Block.OUTLINE,
        empty?ClipContext.Fluid.SOURCE_ONLY:ClipContext.Fluid.NONE,mc.player));}
    public static final class InspectRay implements PilotController.Job {
        public boolean tick(PilotController c)throws Exception {var mc=Actions.mc();boolean empty=mc.player.getMainHandItem().is(net.minecraft.world.item.Items.BUCKET);var hit=ray(empty);var p=hit.getBlockPos();
            Files.writeString(c.directory.resolve("last-bucket-ray.json"),Jsons.GSON.toJson(Jsons.obj("type",hit.getType().toString(),"x",p.getX(),"y",p.getY(),"z",p.getZ(),
                "face",hit.getDirection().getName(),"held",WorldView.stack(mc.player.getMainHandItem()),"emptyBucketRay",empty,
                "eyeX",mc.player.getEyePosition().x,"eyeY",mc.player.getEyePosition().y,"eyeZ",mc.player.getEyePosition().z)),StandardCharsets.UTF_8);return true;}
    }
    public WaterSource(JsonObject data){pos=Actions.pos(data);source=data.has("source")?Actions.pos(data.getAsJsonObject("source")):null;stand=data.has("stand")?Actions.pos(data.getAsJsonObject("stand")):null;}
    public String description(){return "Normal contained water bucket placement (phase "+phase+")";}
    private void save(PilotController c,String state)throws Exception {Files.writeString(c.directory.resolve("last-water-source.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"state",state,
        "x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),"phase",phase,"detail",detail,"waterBuckets",WorldView.count(WATER))),StandardCharsets.UTF_8);}
    private void select(PlanModel.Material item){var mc=Actions.mc();for(int i=0;i<36;i++)if(WorldView.material(mc.player.getInventory().getItem(i)).equals(item)){
        if(i>=9){Actions.click(mc.player.inventoryMenu.containerId,i,8,ClickType.SWAP);i=8;}Actions.select(i);return;}throw new IllegalStateException("Required normal bucket absent: "+item.id());}
    private boolean approach(PilotController c,BlockPos p)throws Exception {if(approach==null){boolean explicit=stand!=null&&p.equals(pos);BlockPos destination=explicit?stand:p;if(explicit)ServerRules.checkBuild(stand);
        approach=PilotJobs.create(Jsons.obj("type",explicit?"walk":"walk_near","x",destination.getX(),"y",destination.getY(),"z",destination.getZ()));}
        if(!approach.tick(c))return false;approach=null;Actions.releaseControls();return true;}
    private void contained(){var mc=Actions.mc();ServerRules.checkBuild(pos);ServerRules.checkBuild(pos.below());
        if(!LoadedWorld.has(pos)||!mc.level.getBlockState(pos.below()).isFaceSturdy(mc.level,pos.below(),Direction.UP))throw new IllegalStateException("Contained water floor is not observed");
        for(Direction side:Direction.Plane.HORIZONTAL){BlockPos wall=pos.relative(side);ServerRules.checkBuild(wall);
            if(!LoadedWorld.has(wall)||!mc.level.getBlockState(wall).isFaceSturdy(mc.level,wall,side.getOpposite()))throw new IllegalStateException("Water cell must have four own solid walls");}}
    public boolean tick(PilotController c)throws Exception {
        var mc=Actions.mc();contained();if(++elapsed>1200)throw new IllegalStateException("Water placement timed out; inspect checkpoint");if(wait-->0)return false;
        if(phase==0){Actions.closeMenu();if(mc.level.getFluidState(pos).is(Fluids.WATER)&&mc.level.getFluidState(pos).isSource()){save(c,"existing_source_verified");return true;}
            if(!mc.level.getBlockState(pos).isAir())throw new IllegalStateException("Water destination is occupied");
            if(WorldView.count(WATER)>0)phase=4;else{if(source==null||WorldView.count(BUCKET)<1)throw new IllegalStateException("Need a water bucket or an own Sink and an empty bucket");
                ServerRules.checkBuild(source);if(!net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(source).getBlock()).toString().equals("cookingforblockheads:sink"))throw new IllegalStateException("Recorded own Sink not observed");phase=1;}}
        if(phase==1){if(!approach(c,source))return false;select(BUCKET);waterBefore=WorldView.count(WATER);phase=2;wait=5;return false;}
        if(phase==2){save(c,"fill_intent");Actions.useBlock(source,Direction.UP);phase=3;wait=12;return false;}
        if(phase==3){if(WorldView.count(WATER)!=waterBefore+1)throw new IllegalStateException("Normal Sink bucket fill was not confirmed; no click was repeated");phase=4;}
        if(phase==4){if(!approach(c,pos))return false;select(WATER);waterBefore=WorldView.count(WATER);Actions.lookAt(Vec3.atCenterOf(pos.below()).add(0,0.49,0));phase=5;wait=6;return false;}
        if(phase==5){if(!mc.level.getBlockState(pos).isAir()||!WorldView.material(mc.player.getMainHandItem()).equals(WATER))throw new IllegalStateException("Bucket or water destination changed");
            var hit=ray(false);if(hit.getType()!=HitResult.Type.BLOCK||!hit.getBlockPos().equals(pos.below())||hit.getDirection()!=Direction.UP){detail="Bucket ray hit "+hit.getType()+" "+hit.getBlockPos()+" "+hit.getDirection();save(c,"blocked_bucket_ray");
                throw new IllegalStateException("Bucket ray does not reach the target floor's upper face; open the fixture before filling; no bucket use sent");}
            ServerRules.checkItemUse();save(c,"placement_intent");mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);mc.player.swing(InteractionHand.MAIN_HAND);phase=6;wait=12;return false;}
        if(!mc.level.getFluidState(pos).is(Fluids.WATER)||!mc.level.getFluidState(pos).isSource()||WorldView.count(WATER)!=waterBefore-1)throw new IllegalStateException("Normal water bucket placement was not confirmed; inspect before retrying");
        save(c,"completed_verified");return true;
    }
    public void cancel(){if(approach!=null)approach.cancel();Actions.releaseControls();try{save(PilotMod.CONTROLLER,"interrupted_needs_replan");}catch(Exception ignored){}}
}
