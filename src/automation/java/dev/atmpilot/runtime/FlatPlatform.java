package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Bounded connected floor construction using existing flight and ordinary visible block-face clicks. */
public final class FlatPlatform implements PilotController.Job,ReflexResumable {
    private static final Set<String> SAFE=Set.of("minecraft:cobblestone","minecraft:cobbled_deepslate","minecraft:mossy_cobblestone","minecraft:stone","minecraft:glass","minecraft:cherry_planks","minecraft:oak_planks","mob_grinding_utils:tinted_glass","pipez:item_pipe","mekanism:basic_universal_cable","mekanism:advanced_universal_cable","mekanism:ultimate_universal_cable","ae2:fluix_glass_cable","sfm:cable","industrialforegoingsouls:soul_network_pipe");
    private final List<BlockPos> positions=new ArrayList<>();private final List<String> materials=new ArrayList<>();private final int y;
    private int index,wait,placed,elapsed;private boolean initialized,pending;private String expected;private BlockPos clicked,steeringTarget;
    private final FlightSteering steering=new FlightSteering();private final FlightControls controls=new FlightControls();
    public FlatPlatform(JsonObject d){
        if(d.has("positions")){
            var rows=d.getAsJsonArray("positions");if(rows.isEmpty()||rows.size()>4096)throw new IllegalArgumentException("Connected construction requires 1..4096 positions");
            var unique=new HashSet<BlockPos>();for(var v:rows){var p=Actions.pos(v.getAsJsonObject());if(!unique.add(p))throw new IllegalArgumentException("Duplicate construction position");positions.add(p);}
            y=positions.getFirst().getY();for(var v:d.getAsJsonArray("materials")){String id=v.getAsString();if(!SAFE.contains(id)||materials.contains(id))throw new IllegalArgumentException("Unsupported connected construction material");materials.add(id);}if(materials.isEmpty())throw new IllegalArgumentException("Materials are required");return;
        }
        int minX=Jsons.integer(d,"minX",0),maxX=Jsons.integer(d,"maxX",-1),minZ=Jsons.integer(d,"minZ",0),maxZ=Jsons.integer(d,"maxZ",-1);y=Jsons.integer(d,"y",-4096);
        long size=((long)maxX-minX+1)*((long)maxZ-minZ+1);if(minX>maxX||minZ>maxZ||size<1||size>4096)throw new IllegalArgumentException("Platform must contain 1..4096 cells");
        for(var v:d.getAsJsonArray("materials")){String id=v.getAsString();if(!SAFE.contains(id)||materials.contains(id))throw new IllegalArgumentException("Only explicit plain solid platform materials are supported");materials.add(id);}if(materials.isEmpty())throw new IllegalArgumentException("Platform materials are required");
        for(int z=minZ;z<=maxZ;z++)for(int n=0;n<=maxX-minX;n++)positions.add(new BlockPos(((z-minZ)&1)==0?minX+n:maxX-n,y,z));
    }
    public String description(){return "Build own platform "+index+" / "+positions.size()+" using normal flight and placement";}
    private String id(BlockPos p){
        var mc=Actions.mc();String id=BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString();
        if(id.equals("ae2:cable_bus"))try{
            Class<?> host=Class.forName("appeng.api.parts.IPartHost",true,PilotJobs.class.getClassLoader());Object entity=mc.level.getBlockEntity(p);
            if(host.isInstance(entity)){Object part=host.getMethod("getPart",Direction.class).invoke(entity,new Object[]{null});if(part!=null){Object item=Class.forName("appeng.api.parts.IPart",true,PilotJobs.class.getClassLoader()).getMethod("getPartItem").invoke(part);if(item instanceof net.minecraft.world.item.Item value)return BuiltInRegistries.ITEM.getKey(value).toString();}}
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot verify the native AE center cable",e);}return id;
    }
    private boolean existing(BlockPos p){String id=id(p);return materials.contains(id)||id.equals("utilitarian:angel_block");}
    private Vec3 facePoint(BlockPos q,Direction face){
        var mc=Actions.mc();var bounds=mc.level.getBlockState(q).getShape(mc.level,q).bounds();double x=(bounds.minX+bounds.maxX)/2,z=(bounds.minZ+bounds.maxZ)/2,cy=(bounds.minY+bounds.maxY)/2;
        switch(face){case EAST->x=bounds.maxX-.001;case WEST->x=bounds.minX+.001;case SOUTH->z=bounds.maxZ-.001;case NORTH->z=bounds.minZ+.001;case UP->cy=bounds.maxY-.001;case DOWN->cy=bounds.minY+.001;}
        return new Vec3(q.getX()+x,q.getY()+cy,q.getZ()+z);
    }
    private void report(PilotController c,String state)throws Exception{Files.writeString(c.directory.resolve("last-platform-build.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"state",state,"dimension",Actions.mc().level.dimension().location().toString(),"first",Jsons.obj("x",positions.getFirst().getX(),"y",positions.getFirst().getY(),"z",positions.getFirst().getZ()),"last",Jsons.obj("x",positions.getLast().getX(),"y",positions.getLast().getY(),"z",positions.getLast().getZ()),"verifiedCells",index,"total",positions.size(),"placed",placed,"materials",materials)),StandardCharsets.UTF_8);}
    private boolean approach(BlockPos p){
        var mc=Actions.mc();var player=mc.player;var velocity=player.getDeltaMovement();double dx=p.getX()+.5-player.getX(),dz=p.getZ()+.5-player.getZ(),dy=p.getY()+2.35-player.getY(),distance=Math.hypot(dx,dz);
        Actions.releaseControls();double intent=Math.min(3,distance);
        if(!p.equals(steeringTarget)){steeringTarget=p;steering.reset();}
        Vec3 direction=distance>.001?new Vec3(dx/distance,0,dz/distance):Vec3.ZERO;
        Vec3 proposed=new Vec3(MotionPolicy.preview(dx,velocity.x,direction.x*intent),MotionPolicy.preview(dy,velocity.y,Math.max(-1,Math.min(1,dy))),MotionPolicy.preview(dz,velocity.z,direction.z*intent));
        var swept=player.getBoundingBox().expandTowards(proposed).inflate(.04,0,.04);
        for(BlockPos q:BlockPos.betweenClosed(BlockPos.containing(swept.minX,swept.minY,swept.minZ),BlockPos.containing(swept.maxX,swept.maxY,swept.maxZ)))if(!LoadedWorld.has(q)||!mc.level.getFluidState(q).isEmpty())throw new IllegalStateException("Platform flight corridor is unloaded or wet");
        if(!mc.level.noCollision(player,swept))throw new IllegalStateException("Platform flight corridor is obstructed");
        controls.vertical(p.getY()+2.35);FlightControls.horizontal(steering.horizontal(dx,dz,velocity.x,velocity.z,player.getYRot(),false,player.getAbilities().getFlyingSpeed()));
        if(distance<.16&&Math.abs(dy)<.25&&velocity.horizontalDistance()<.045&&Math.abs(velocity.y)<.035){Actions.releaseControls();return true;}return false;
    }
    private Direction support(BlockPos p){
        var mc=Actions.mc();Direction best=null;double distance=Double.POSITIVE_INFINITY;
        for(Direction face:Direction.values()){BlockPos q=p.relative(face.getOpposite());if(!LoadedWorld.has(q)||(!SAFE.contains(id(q))&&!id(q).equals("utilitarian:angel_block"))||mc.level.getBlockState(q).getShape(mc.level,q).isEmpty())continue;
            if(mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(q))>mc.player.blockInteractionRange()+.2)continue;
            Vec3 point=facePoint(q,face);double d=mc.player.getEyePosition().distanceTo(point);
            if(d>mc.player.blockInteractionRange()||d>=distance)continue;var hit=mc.level.clip(new ClipContext(mc.player.getEyePosition(),point,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
            if(hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(q)){distance=d;best=face;}
        }return best;
    }
    public boolean tick(PilotController c)throws Exception{
        var mc=Actions.mc();elapsed++;if(mc.screen!=null||!mc.player.containerMenu.getCarried().isEmpty()||!mc.player.getAbilities().flying)throw new IllegalStateException("Platform construction requires an empty cursor, closed screen and existing flight");
        if(!initialized){int required=0;for(BlockPos p:positions){ServerRules.checkBuild(p);if(!LoadedWorld.has(p))throw new IllegalStateException("Platform cell is not loaded");if(!existing(p)){if(!mc.level.getBlockState(p).isAir())throw new IllegalStateException("Platform contains an unexpected existing block");required++;}}
            int stock=0;for(int n=0;n<36;n++){var s=mc.player.getInventory().getItem(n);if(materials.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).toString()))stock+=s.getCount();}if(stock<required)throw new IllegalStateException("Insufficient carried platform materials: "+stock+" / "+required);initialized=true;report(c,"building");}
        if(index>=positions.size()){Actions.releaseControls();report(c,"completed_verified");return true;}
        BlockPos p=positions.get(index);ServerRules.checkBuild(p);
        if(pending){Actions.releaseControls();if(++wait<5)return false;if(id(p).equals(expected)){pending=false;index++;placed++;wait=0;if(placed%32==0)report(c,"building");return false;}if(wait>100)throw new IllegalStateException("Platform click did not produce the expected block at "+p);return false;}
        if(existing(p)){index++;return false;}if(!mc.level.getBlockState(p).isAir())throw new IllegalStateException("Platform target changed");
        if(wait>0){wait--;return false;}
        Direction face=support(p);if(face==null||Math.abs(mc.player.getY()-(p.getY()+2))>.35){if(!approach(p))return false;face=support(p);if(face==null)throw new IllegalStateException("No visible connected placement support at "+p);}
        Actions.releaseControls();var held=mc.player.getMainHandItem();String current=BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        if(!materials.contains(current)||held.isEmpty()){
            int slot=-1;for(String wanted:materials){for(int n=0;n<36;n++)if(BuiltInRegistries.ITEM.getKey(mc.player.getInventory().getItem(n).getItem()).toString().equals(wanted)&&!mc.player.getInventory().getItem(n).isEmpty()){slot=n;break;}if(slot>=0)break;}
            if(slot<0)throw new IllegalStateException("Platform material ran out");if(slot>=9){Actions.click(0,slot,6,ClickType.SWAP);Actions.select(6);}else Actions.select(slot);wait=2;return false;
        }
        expected=current;clicked=p.relative(face.getOpposite());ServerRules.checkBuild(clicked);ServerRules.checkOpen(clicked);Actions.checkReach(clicked);Vec3 point=facePoint(clicked,face);Actions.lookAt(point);
        var result=mc.gameMode.useItemOn(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND,new BlockHitResult(point,face,clicked,false));if(result.consumesAction())mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);pending=true;wait=0;c.note(description());return false;
    }
    public void cancel(){Actions.releaseControls();try{report(PilotMod.CONTROLLER,"interrupted_reobserve_before_resume");}catch(Exception ignored){}}
    public boolean resumeAfterLocalCombat(){return Actions.mc().player.getHealth()>12&&Actions.mc().screen==null&&Actions.mc().player.containerMenu.getCarried().isEmpty();}
}
