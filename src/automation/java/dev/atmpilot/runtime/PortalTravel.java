package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Walk through one specifically observed existing portal; the server performs ordinary travel. */
public final class PortalTravel implements PilotController.Job,ReflexResumable {
    private final String name;
    private JsonObject site,report;
    private BlockPos entry,stand;
    private PilotController.Job child;
    private int phase,elapsed,stable;
    public PortalTravel(JsonObject data){name=Jsons.str(data,"name","");}
    public String description(){return "Authorized survival travel: existing portal "+name;}
    private void save(String state)throws Exception{
        report.addProperty("state",state);report.addProperty("updatedAt",Instant.now().toString());
        Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-portal-travel.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);
    }
    public boolean tick(PilotController controller)throws Exception{
        var mc=Actions.mc();
        if(site==null){
            var rows=ServerRules.snapshot().getAsJsonArray("verifiedSurvivalPortals");
            if(rows!=null)for(var value:rows){var candidate=value.getAsJsonObject();if(name.equals(Jsons.str(candidate,"name",""))&&Jsons.bool(candidate,"observedExisting",false)){site=candidate.deepCopy();break;}}
            if(site==null)throw new IllegalStateException("No user-confirmed existing portal: "+name);
            if(!mc.level.dimension().location().toString().equals(Jsons.str(site,"dimension","")))throw new IllegalStateException("Portal source dimension changed");
            entry=Actions.pos(site.getAsJsonObject("entry"));stand=Actions.pos(site.getAsJsonObject("stand"));
            if(entry.distSqr(stand)>4||mc.player.position().distanceTo(Vec3.atBottomCenterOf(stand))>32)throw new IllegalStateException("Approach the observed portal locally first");
            if(!LoadedWorld.has(entry)||!LoadedWorld.has(entry.above())||!mc.level.getBlockState(entry).is(Blocks.NETHER_PORTAL)||!mc.level.getBlockState(entry.above()).is(Blocks.NETHER_PORTAL))throw new IllegalStateException("The observed existing portal changed");
            if(!LoadedWorld.has(stand)||!mc.level.getBlockState(stand).getCollisionShape(mc.level,stand).isEmpty()||!mc.level.getFluidState(stand).isEmpty()||!mc.level.getBlockState(stand.below()).isFaceSturdy(mc.level,stand.below(),net.minecraft.core.Direction.UP))throw new IllegalStateException("Portal approach is no longer a clear supported stand");
            Actions.closeMenu();report=Jsons.obj("startedAt",Instant.now().toString(),"portal",site);save("approaching_existing_portal");
            child=new Flight.Fly(Jsons.obj("x",stand.getX(),"y",stand.getY(),"z",stand.getZ(),"cruiseY",Math.max((int)Math.ceil(mc.player.getY())+6,stand.getY()+6),"hover",false));
        }
        if(++elapsed>1200)throw new IllegalStateException("Existing portal arrival was not verified; no portal was created or edited");
        String dimension=mc.level.dimension().location().toString();
        if(dimension.equals(Jsons.str(site,"targetDimension",""))){
            Actions.releaseControls();
            boolean ready=LoadedWorld.has(mc.player.blockPosition())&&LoadedWorld.has(mc.player.blockPosition().above())&&!(mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen);
            stable=ready?stable+1:0;
            if(stable>=10){report.add("arrival",Jsons.obj("dimension",dimension,"x",mc.player.getX(),"y",mc.player.getY(),"z",mc.player.getZ()));save("arrived_verified");return true;}return false;
        }
        if(!dimension.equals(Jsons.str(site,"dimension","")))throw new IllegalStateException("Existing portal reached an unexpected dimension");
        if(child!=null){
            if(child.tick(controller)){
                child=null;Actions.releaseControls();phase++;
                if(phase==1){child=PilotJobs.create(Jsons.obj("type","walk","x",entry.getX(),"y",entry.getY(),"z",entry.getZ()));save("walking_into_existing_portal");}
                else save("waiting_for_server_portal_travel");
            }
            return false;
        }
        Actions.releaseControls();return false;
    }
    public void cancel(){if(child!=null)child.cancel();Actions.releaseControls();if(report!=null)try{save("interrupted_reobserve_before_resume");}catch(Exception ignored){}}
    public boolean resumeAfterLocalCombat(){return false;}
}
