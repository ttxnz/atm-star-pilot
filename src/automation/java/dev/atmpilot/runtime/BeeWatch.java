package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Local observation, ordinary flight and one-click cages for the two starter nests. */
public final class BeeWatch implements PilotController.Job {
    private final int requested,timeout;private final Set<String> types=Set.of("productivebees:green_carpenter_bee","productivebees:yellow_black_carpenter_bee","productivebees:digger_bee","productivebees:mason_bee");
    private final JsonArray nests;private final JsonArray captured=new JsonArray();private final Set<UUID> done=new HashSet<>();private PilotController.Job child;private BlockPos park;private UUID targetUuid;private int targetId,phase,elapsed,attempts;private String started,targetType;
    public BeeWatch(JsonObject data){requested=Jsons.integer(data,"count",2);timeout=Jsons.integer(data,"timeoutTicks",24000);nests=data.getAsJsonArray("nests").deepCopy();if(requested<1||requested>3||timeout<20||timeout>36000||nests.isEmpty()||nests.size()>8)throw new IllegalArgumentException("Expected bounded starter-bee watch and own nests");}
    public String description(){return "Watch own starter nests and cage "+captured.size()+" / "+requested+" bees (phase "+phase+")";}
    private void report(String state)throws Exception{Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-bee-watch.json"),Jsons.GSON.toJson(Jsons.obj("startedAt",started,"updatedAt",Instant.now().toString(),"state",state,"requested",requested,"captured",captured,"targetId",targetId,"targetUuid",targetUuid==null?null:targetUuid.toString(),"phase",phase,"elapsedTicks",elapsed)),StandardCharsets.UTF_8);}
    private Entity target(){var e=Actions.mc().level.getEntity(targetId);return e!=null&&e.isAlive()&&targetUuid.equals(e.getUUID())?e:null;}
    private boolean allowed(Entity e){return e!=null&&types.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString())&&!done.contains(e.getUUID())&&(OwnBeeRecords.tracked(e)||OwnBeeRecords.register(e,nests));}
    private PilotController.Job fly(BlockPos p){var mc=Actions.mc();return new Flight.Fly(Jsons.obj("x",p.getX(),"y",p.getY(),"z",p.getZ(),"cruiseY",Math.max((int)Math.ceil(mc.player.getY())+3,p.getY()+6),"hover",true));}
    private void park(boolean finish)throws Exception{child=fly(park);phase=finish?4:3;report(finish?"parking_after_capture":"waiting_from_safe_hover");}
    private void approach(Entity e)throws Exception{
        if(++attempts>3)throw new IllegalStateException("Bee moved beyond three bounded approach attempts");
        var mc=Actions.mc();if(mc.player.distanceTo(e)<=mc.player.entityInteractionRange()&&mc.player.hasLineOfSight(e)){child=new BeeCapture(Jsons.obj("entityId",e.getId(),"expectedType",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString()));phase=2;}
        else {child=new GroundBeeApproach(e);phase=1;}report("approaching_visible_bee");
    }
    public boolean tick(PilotController c)throws Exception{try{return step(c);}catch(Exception|LinkageError e){try{report("stopped_needs_replan");}catch(Exception ignored){}throw e;}}
    private boolean step(PilotController c)throws Exception {
        var mc=Actions.mc();if(++elapsed>timeout)throw new IllegalStateException("Bee watch timed out; captured cages and checkpoint retained");
        if(park==null){started=Instant.now().toString();park=new BlockPos(mc.player.blockPosition().getX(),(int)Math.ceil(mc.player.getY()),mc.player.blockPosition().getZ());if(!mc.player.getAbilities().flying)throw new IllegalStateException("Begin the nest watch from verified safe hover");
            for(var value:nests){BlockPos p=Actions.pos(value.getAsJsonObject());ServerRules.checkBuild(p);if(!LoadedWorld.has(p)||!BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString().endsWith("_nest"))throw new IllegalStateException("Own starter nest changed");}report("waiting_for_visible_bees");}
        if(phase==1){Entity e=target();if(!allowed(e)){child.cancel();Actions.releaseControls();park(false);return false;}if(!child.tick(c))return false;Actions.releaseControls();approach(e);return false;}
        if(phase==2){
            Entity e=target();boolean complete;
            try{complete=child.tick(c);}catch(IllegalStateException error){if(child instanceof BeeCapture capture&&!capture.interactionSent()&&attempts<3&&allowed(e)){child.cancel();Actions.releaseControls();approach(e);return false;}throw error;}
            if(!complete)return false;captured.add(Jsons.obj("entityId",targetId,"uuid",targetUuid.toString(),"type",targetType,"capturedAt",Instant.now().toString()));done.add(targetUuid);child=null;phase=0;report("cage_verified");
        }
        if(phase==3||phase==4){if(!child.tick(c))return false;Actions.releaseControls();boolean finish=phase==4;child=null;phase=0;report(finish?"completed_verified":"waiting_for_visible_bees");if(finish)return true;}
        if(captured.size()>=requested){park(true);return false;}
        for(var e:mc.level.entitiesForRendering())if(allowed(e)&&e.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(park))<48*48){targetId=e.getId();targetUuid=e.getUUID();targetType=BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();attempts=0;approach(e);return false;}
        if(mc.player.getY()<park.getY()-3){park(false);return false;}Actions.releaseControls();return false;
    }
    public void cancel(){if(child!=null)child.cancel();Actions.releaseControls();try{report("interrupted_needs_replan");}catch(Exception ignored){}}
}
