package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Respawn is handled by the observer. This job returns once, retrieves an owned grave and verifies inventory. */
public final class Recover implements PilotController.Job {
    private final JsonObject checkpoint;private BlockPos target,grave;private String dimension;private int phase,elapsed,wait,retrievalStarted;private PilotController.Job approach;private boolean clicked;
    public Recover(JsonObject data){checkpoint=data.deepCopy();target=BlockPos.containing(Jsons.decimal(data,"x",0),Jsons.decimal(data,"y",0),Jsons.decimal(data,"z",0));dimension=Jsons.str(data,"dimension","");}
    public String description(){return "Recover own Tombstone grave (phase "+phase+")";}
    private void keyLocation()throws ReflectiveOperationException{
        var player=Actions.mc().player;Class<?> keyType=Class.forName("ovh.corail.tombstone.item.ItemGraveKey");
        BlockPos best=null;double nearest=64*64;
        for(int i=0;i<36;i++){
            ItemStack stack=player.getInventory().getItem(i);if(stack.isEmpty()||!keyType.isInstance(stack.getItem()))continue;
            Optional<?> owner=(Optional<?>)keyType.getMethod("getOwnerId",ItemStack.class).invoke(stack.getItem(),stack);
            if(owner.isEmpty()||!player.getUUID().equals(owner.get()))continue;
            Object location=keyType.getMethod("getTombPos",ItemStack.class).invoke(stack.getItem(),stack);
            if(location==null||(Boolean)location.getClass().getMethod("isOrigin").invoke(location))continue;
            BlockPos candidate=(BlockPos)location.getClass().getMethod("getPos").invoke(location);
            if(!dimension.equals(location.getClass().getMethod("getDimString").invoke(location).toString()))continue;
            double distance=target.getY()<0?Math.pow(candidate.getX()-target.getX(),2)+Math.pow(candidate.getZ()-target.getZ(),2):candidate.distSqr(target);if(distance<=nearest){nearest=distance;best=candidate;}
        }
        if(best!=null){target=best;DeathRecovery.record("targetFromOwnedKey",Jsons.obj("dimension",dimension,"x",target.getX(),"y",target.getY(),"z",target.getZ()));}
    }
    private boolean nearby(){var mc=Actions.mc();return mc.level.dimension().location().toString().equals(dimension)&&mc.player.position().distanceTo(Vec3.atBottomCenterOf(target))<24;}
    private BlockPos locate(){
        var mc=Actions.mc();BlockPos best=null;double distance=24*24;
        for(int x=Math.floorDiv(target.getX()-24,16);x<=Math.floorDiv(target.getX()+24,16);x++)for(int z=Math.floorDiv(target.getZ()-24,16);z<=Math.floorDiv(target.getZ()+24,16);z++){
            var chunk=LoadedWorld.chunk(x,z);if(chunk==null)continue;
            for(var entity:chunk.getBlockEntities().values()){
                BlockPos pos=entity.getBlockPos();double candidate=pos.distSqr(target);
                if(candidate<=distance&&OwnGraves.isOwned(pos)){best=pos;distance=candidate;}
            }
        }
        return best;
    }
    private void emptyHand(){
        var mc=Actions.mc();for(int i=0;i<9;i++)if(mc.player.getInventory().getItem(i).isEmpty()){Actions.select(i);return;}
        for(int i=9;i<36;i++)if(mc.player.getInventory().getItem(i).isEmpty()){
            Actions.click(mc.player.inventoryMenu.containerId,i,8,ClickType.SWAP);Actions.select(8);return;
        }
        throw new IllegalStateException("No empty hand available for safe grave interaction");
    }
    public boolean tick(PilotController controller)throws Exception{
        if(!DeathRecovery.active(controller))throw new IllegalStateException("Recovery must be scheduled from a recorded death checkpoint");
        var mc=Actions.mc();if(++elapsed>1800)throw new IllegalStateException("Recovery timed out; checkpoint retained for replanning");
        if(wait-->0)return false;
        if(phase==0){
            if(!mc.player.getUUID().toString().equals(Jsons.str(checkpoint,"playerUuid","")))throw new IllegalStateException("Recovery checkpoint belongs to another player");
            Actions.closeMenu();
            JsonObject current=DeathRecovery.inventorySnapshot();
            if(Jsons.bool(checkpoint,"baselineAvailable",false)&&RecoveryLedger.missing(checkpoint.getAsJsonObject("before"),current).isEmpty()){
                DeathRecovery.record("inventoryVerifiedBeforeSafety",current);approach=new SafeHome(Jsons.obj("recoveryIncident",Jsons.str(checkpoint,"recordedAt","")));phase=4;DeathRecovery.phase("escaping_after_retrieval");return false;
            }
            keyLocation();
            if(!nearby()){
                if(WaypointAccess.available()){
                    JsonObject closest=null;double distance=16*16;
                    for(var value:ServerRules.snapshot().getAsJsonArray("verifiedWaypoints")){var point=value.getAsJsonObject();if(!dimension.equals(Jsons.str(point,"dimension",""))||!Jsons.bool(point,"observedSafe",false)||Jsons.bool(point,"hover",false)||Jsons.bool(point,"storageAccessOnly",false))continue;double candidate=Actions.pos(point).above().distSqr(target);if(candidate<distance){closest=point;distance=candidate;}}
                    if(closest!=null){String name=Jsons.str(closest,"name","");DeathRecovery.record("nativeWaypointReturn",new JsonPrimitive(name));approach=new WaypointAccess.Travel(Jsons.obj("name",name));phase=5;DeathRecovery.phase("returning");return false;}
                }
                if(!Jsons.bool(checkpoint,"backSent",false)){
                    if(Jsons.bool(checkpoint,"backIntent",false))throw new IllegalStateException("Earlier /back outcome is unknown; inspect before retrying");
                    DeathRecovery.record("backIntent",new JsonPrimitive(true));
                    try{SurvivalCommands.send("back");}
                    catch(SurvivalCommandCooldown cooldown){
                        DeathRecovery.record("backIntent",new JsonPrimitive(false));
                        wait=(int)Math.min(40,Math.max(1,(cooldown.remainingMillis()+49)/50));
                        controller.note("Waiting for the known local /back cooldown; no command was sent");return false;
                    }
                    DeathRecovery.record("backSent",new JsonPrimitive(true));
                }
                DeathRecovery.phase("returning");
            }
            phase=1;wait=10;return false;
        }
        if(phase==5){if(!approach.tick(controller))return false;phase=1;wait=40;return false;}
        if(phase==1){
            if(!nearby()){if(elapsed>600)throw new IllegalStateException("/back did not return near the recorded grave; command was not repeated");return false;}
            grave=locate();if(grave==null){if(elapsed>900)throw new IllegalStateException("No client-synchronized grave owned by this player was found near the death/key location");return false;}
            DeathRecovery.record("confirmedGrave",Jsons.obj("dimension",dimension,"x",grave.getX(),"y",grave.getY(),"z",grave.getZ(),"ownerUuid",mc.player.getUUID().toString()));
            DeathRecovery.phase("locating");approach=PilotJobs.create(Jsons.obj("type","walk_near","x",grave.getX(),"y",grave.getY(),"z",grave.getZ()));phase=2;
        }
        if(phase==2){
            if(!OwnGraves.isOwned(grave))throw new IllegalStateException("Grave ownership changed before retrieval");
            if(!approach.tick(controller))return false;Actions.releaseControls();phase=3;wait=10;return false;
        }
        if(phase==3){
            if(!clicked){
                if(!OwnGraves.isOwned(grave))throw new IllegalStateException("Cannot confirm that the grave is the player's own");
                emptyHand();DeathRecovery.phase("retrieving");Actions.useBlock(grave,Direction.UP);clicked=true;retrievalStarted=elapsed;wait=8;return false;
            }
            if(OwnGraves.isOwned(grave)){if(elapsed-retrievalStarted<=100)return false;throw new IllegalStateException("Owned grave was not retrieved; inventory and checkpoint retained");}
            var after=DeathRecovery.inventorySnapshot();JsonObject missing=RecoveryLedger.missing(checkpoint.getAsJsonObject("before"),after);
            if(!missing.isEmpty()&&elapsed-retrievalStarted<=100)return false;
            DeathRecovery.record("inventoryVerifiedBeforeSafety",after);DeathRecovery.record("missingBeforeSafety",missing);approach=new SafeHome(Jsons.obj("recoveryIncident",Jsons.str(checkpoint,"recordedAt","")));phase=4;DeathRecovery.phase("escaping_after_retrieval");return false;
        }
        if(phase==4){
            if(!approach.tick(controller))return false;var after=DeathRecovery.inventorySnapshot();JsonObject missing=RecoveryLedger.missing(checkpoint.getAsJsonObject("before"),after);
            DeathRecovery.record("postRecoverySafety",Jsons.obj("state","safe_home_hover","dimension",mc.level.dimension().location().toString(),"x",mc.player.getX(),"y",mc.player.getY(),"z",mc.player.getZ()));
            DeathRecovery.finished(after,missing);controller.note(missing.isEmpty()?"Own inventory restored; safely hovering at home; replan interrupted work":"At safe home; inspect missing quantities before replanning");return true;
        }
        return false;
    }
    public void cancel(){if(approach!=null)approach.cancel();Actions.releaseControls();}
}
