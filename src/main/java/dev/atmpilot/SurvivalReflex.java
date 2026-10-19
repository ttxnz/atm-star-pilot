package dev.atmpilot;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ClickType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

/** Local survival responses use the same ordinary, guarded jobs as the external planner. */
public final class SurvivalReflex {
    private SurvivalReflex(){}
    private static boolean armed,held,internal,pendingEscape,enabled;
    private static String escapeId="",parkId="",reason="Not armed";
    private static long idleSince=-1,coolUntil;private static int cursorWait;
    public static boolean manualReason(String value){return SurvivalPolicy.manualReason(value);}
    public static boolean eligible(boolean setting,boolean authorized,boolean manualHold,boolean alive,boolean recovering){return SurvivalPolicy.eligible(setting,authorized,manualHold,alive,recovering);}
    public static JsonObject status(){return Jsons.obj("enabled",enabled,"armed",armed,"manualHold",held,"pendingEscape",pendingEscape,"safeReturnJob",escapeId,"parkingJob",parkId,"reason",reason);}
    public static boolean active(PilotController c){return !escapeId.isEmpty()&&escapeId.equals(Jsons.str(c.status(),"id",""));}
    private static void save(){try{Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-survival-reflex.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"status",status(),"controller",PilotMod.CONTROLLER.status())),StandardCharsets.UTF_8);}catch(Exception e){PilotMod.LOG.warn("Cannot save survival reflex checkpoint",e);}}
    public static void manualInput(){held=true;armed=false;pendingEscape=false;escapeId="";parkId="";idleSince=-1;reason="Manual control takes precedence";save();}
    public static void started(PilotController c){if(internal||Jsons.bool(DeathRecovery.status(),"manualHold",false))return;armed=true;held=false;pendingEscape=false;escapeId="";parkId="";idleSince=-1;coolUntil=0;cursorWait=0;reason="Automation session active";}
    public static void stopped(PilotController c,String cause){
        if(manualReason(cause)){manualInput();return;}
        if(cause.equals("Death recorded for recovery")){escapeId="";parkId="";pendingEscape=false;idleSince=-1;return;}
        var mc=Minecraft.getInstance();if(mc.player==null||mc.level==null){armed=false;pendingEscape=false;return;}
        if(active(c)){escapeId="";held=true;armed=false;pendingEscape=false;reason="Safety return failed: "+cause;save();return;}
        parkId="";idleSince=-1;
        if(armed&&!held&&mc.player.getHealth()>1){
            pendingEscape=false;reason="Execution stopped; remain on site with local defense: "+cause;save();return;
        }
        if(enabled&&armed&&!held&&mc.player.isAlive()&&mc.player.getHealth()<=1){
            pendingEscape=true;cursorWait=0;reason="Execution stopped: "+cause;save();
        }
    }
    private static void dispatch(PilotController c,JsonObject action,boolean escape)throws Exception {
        internal=true;try{String id=c.start(List.of(PilotJobs.create(action)));if(escape)escapeId=id;else parkId=id;pendingEscape=false;idleSince=-1;save();}finally{internal=false;}
    }
    private static boolean clearCursor(){
        var mc=Actions.mc();var menu=mc.player.containerMenu;var carried=menu.getCarried();if(carried.isEmpty())return true;
        for(var slot:menu.slots)if(slot.container==mc.player.getInventory()&&slot.mayPlace(carried)&&(slot.getItem().isEmpty()||net.minecraft.world.item.ItemStack.isSameItemSameComponents(slot.getItem(),carried))&&slot.getMaxStackSize(carried)-slot.getItem().getCount()>=carried.getCount()){
            Actions.click(menu.containerId,slot.index,0,ClickType.PICKUP);return false;
        }
        return false;
    }
    public static void tick(PilotController c){
        try{
            var mc=Minecraft.getInstance();if(mc.player==null||mc.level==null)return;
            var config=ServerRules.snapshot().getAsJsonObject("survivalGuard");enabled=config!=null&&Jsons.bool(config,"enabled",false);
            if(!eligible(enabled,armed,held,mc.player.isAlive(),DeathRecovery.active(c)))return;
            if(!escapeId.isEmpty()){
                if(c.running())return;escapeId="";coolUntil=c.ticks()+100;reason="Safety return completed";save();
            }
            if(!parkId.isEmpty()){
                if(c.running())return;parkId="";reason="Idle hover completed";save();
            }
            double threshold=Math.min(1,Math.max(.1,Jsons.decimal(config,"healthThreshold",1)));
            if(!pendingEscape&&mc.player.getHealth()<=threshold&&mc.level.dimension().equals(net.minecraft.world.level.Level.END)&&mc.player.getY()<mc.level.getMinBuildHeight()+.5&&mc.player.getDeltaMovement().y<-.2){
                if(c.running())c.stop("Survival reflex: falling below the End floor");pendingEscape=true;cursorWait=0;reason="Void fall detected; return before void damage";save();
            }
            if(!pendingEscape&&c.ticks()>=coolUntil&&mc.player.getHealth()<=threshold){
                if(c.running())c.stop("Survival reflex: low health");pendingEscape=true;cursorWait=0;reason="Low health; return to verified safe home";save();
            }
            if(pendingEscape){
                if(c.running())return;
                if(!mc.player.containerMenu.getCarried().isEmpty()){
                    if(++cursorWait>50){held=true;armed=false;pendingEscape=false;reason="No safe player-inventory space for the cursor; checkpoint retained";save();return;}
                    if(cursorWait%6==1)clearCursor();return;
                }
                dispatch(c,Jsons.obj("type","extension:safe_home","recoveryIncident","survival-"+c.ticks()),true);return;
            }
            if(c.running()||RealtimeCombat.active()||mc.screen!=null||mc.player.getAbilities().flying||mc.level.dimension().location().toString().equals("allthemodium:mining")||!Jsons.bool(config,"idleHover",true)){idleSince=-1;return;}
            if(idleSince<0)idleSince=c.ticks();if(c.ticks()-idleSince<Math.max(10,Math.min(100,Jsons.integer(config,"idleTicks",20))))return;
            if(!Jsons.bool(ServerRules.snapshot(),"allowExistingSurvivalFlight",false))return;
            int y=Math.min(mc.level.getMaxBuildHeight()-10,(int)Math.ceil(mc.player.getY())+Math.max(8,Math.min(24,Jsons.integer(config,"hoverHeight",12))));
            reason="Planner idle; hover above ground";
            dispatch(c,Jsons.obj("type","extension:fly","x",mc.player.blockPosition().getX(),"y",y,"z",mc.player.blockPosition().getZ(),"cruiseY",y+6,"hover",true),false);
        }catch(Exception|LinkageError e){held=true;armed=false;pendingEscape=false;reason="Survival reflex needs attention: "+e;Actions.releaseControls();save();PilotMod.LOG.warn("Survival reflex stopped",e);}
    }
}
