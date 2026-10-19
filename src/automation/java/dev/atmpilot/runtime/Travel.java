package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Only /home [name] and /back. A command being sent is never treated as arrival. */
public final class Travel implements PilotController.Job {
    private final String command;private final JsonObject expected;private final Vec3 expectedPosition;private final boolean verifyOnly;private JsonObject report;private Vec3 origin;private String dimension;private int elapsed,stableTicks,wait;private boolean sent;
    public Travel(JsonObject data){
        command=Jsons.str(data,"command","");expected=data.has("expected")?data.getAsJsonObject("expected").deepCopy():null;verifyOnly=Jsons.bool(data,"verifyOnly",false);
        if(expected!=null){
            double x=Jsons.decimal(expected,"x",Double.NaN),y=Jsons.decimal(expected,"y",Double.NaN),z=Jsons.decimal(expected,"z",Double.NaN);
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>30000000||Math.abs(z)>30000000||Math.abs(y)>4096||Jsons.str(expected,"dimension","").isBlank())throw new IllegalArgumentException("Expected arrival needs a dimension and finite coordinates");
            net.minecraft.resources.ResourceLocation.parse(Jsons.str(expected,"dimension",""));expectedPosition=new Vec3(x,y,z);
        }else expectedPosition=null;
        if(verifyOnly&&expected==null)throw new IllegalArgumentException("Verification-only travel needs an explicit expected arrival");
    }
    public String description(){return "Authorized survival travel: "+command;}
    private void save()throws Exception{Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-travel.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);}
    public boolean tick(PilotController controller)throws Exception{
        var mc=Actions.mc();
        if(elapsed++==0){
            SurvivalCommands.validate(command,ServerRules.snapshot());Actions.closeMenu();origin=mc.player.position();dimension=mc.level.dimension().location().toString();
            if(verifyOnly){
                report=Jsons.GSON.fromJson(Files.readString(controller.directory.resolve("last-travel.json"),StandardCharsets.UTF_8),JsonObject.class);
                if(!command.equals(Jsons.str(report,"command",""))||!java.util.Set.of("sent_unverified","arrival_not_verified","verifying_existing_command").contains(Jsons.str(report,"state","")))throw new IllegalStateException("No matching previously sent command can be verified");
                sent=true;report.addProperty("state","verifying_existing_command");report.addProperty("verificationStartedAt",Instant.now().toString());save();
            }else{
                report=Jsons.obj("observedAt",Instant.now().toString(),"command",command,"state","sending","origin",Jsons.obj("dimension",dimension,"x",origin.x,"y",origin.y,"z",origin.z));save();
            }
        }
        if(wait-->0)return false;
        if(!sent){
            try{SurvivalCommands.send(command);}
            catch(SurvivalCommandCooldown cooldown){
                report.addProperty("state","waiting_local_cooldown");report.addProperty("retryAfterMillis",cooldown.remainingMillis());save();
                wait=(int)Math.min(40,Math.max(1,(cooldown.remainingMillis()+49)/50));return false;
            }
            sent=true;report.addProperty("state","sent_unverified");save();return false;
        }
        String current=mc.level.dimension().location().toString();boolean arrived;
        if(expected!=null){arrived=current.equals(Jsons.str(expected,"dimension",""))&&mc.player.position().distanceTo(expectedPosition)<=4;}
        else arrived=!current.equals(dimension)||mc.player.position().distanceTo(origin)>4;
        arrived=arrived&&LoadedWorld.has(mc.player.blockPosition())&&LoadedWorld.has(mc.player.blockPosition().above())
            &&!(mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen);
        if(!arrived)stableTicks=0;
        if(arrived&&++stableTicks>=10){
            report.addProperty("state","arrived");report.add("arrival",Jsons.obj("dimension",current,"x",mc.player.getX(),"y",mc.player.getY(),"z",mc.player.getZ(),"clientChunksLoaded",true));save();return true;
        }
        if(elapsed>1200){report.addProperty("state","arrival_not_verified");save();throw new IllegalStateException("Travel destination was not verified; command was not repeated");}
        return false;
    }
    public void cancel(){Actions.releaseControls();}
}
