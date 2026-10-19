package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Ordinary flight and the authorized home command, with a durable one-command checkpoint. */
public final class SafeHome implements PilotController.Job {
    private final boolean resume;private final String incident;private JsonObject home,report;private PilotController.Job child;private int phase,apples,healWait;private long appleBefore;private boolean initialized,mapReturn;
    public SafeHome(JsonObject data){resume=Jsons.bool(data,"resume",false);incident=Jsons.str(data,"recoveryIncident","");}
    public String description(){return "Authorized survival travel: safe home and hover (phase "+phase+")";}
    private void save(String state)throws Exception {report.addProperty("state",state);report.addProperty("updatedAt",Instant.now().toString());Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-safe-home.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);}
    private boolean nearHome(){var p=Actions.mc().player;return LoadedWorld.has(p.blockPosition())&&LoadedWorld.has(p.blockPosition().above())&&Actions.mc().level.dimension().location().toString().equals(Jsons.str(home,"dimension",""))&&Math.hypot(p.getX()-Jsons.decimal(home,"x",0),p.getZ()-Jsons.decimal(home,"z",0))<=3&&Math.abs(p.getY()-Jsons.decimal(home,"y",0))<=32;}
    private PilotController.Job hoverHere(){var p=Actions.mc().player;int y=Math.min(Actions.mc().level.getMaxBuildHeight()-10,(int)Math.ceil(p.getY())+12);return new Flight.Fly(Jsons.obj("x",p.blockPosition().getX(),"y",y,"z",p.blockPosition().getZ(),"cruiseY",Math.min(Actions.mc().level.getMaxBuildHeight()-4,y+6),"hover",true));}
    private void returnHome()throws Exception {
        if(mapReturn){
            report.addProperty("mapIntent",true);save("native_map_return_intent");
            child=new WaypointAccess.Travel(Jsons.obj("name","ATM 独立工厂"));phase=2;save("returning_to_own_factory");return;
        }
        var travel=Jsons.obj("command","home","expected",home);
        if(Jsons.bool(report,"homeIntent",false)){
            var file=PilotMod.CONTROLLER.directory.resolve("last-travel.json");JsonObject prior=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!Jsons.str(prior,"command","").equals("home"))throw new IllegalStateException("Safe-home command outcome is unknown; inspect its checkpoint");
            travel.addProperty("verifyOnly",true);
        }else{report.addProperty("homeIntent",true);save("home_intent");}
        child=new Travel(travel);phase=2;save("returning_home");
    }
    public boolean tick(PilotController c)throws Exception {try{return step(c);}catch(Exception|LinkageError e){if(report!=null){report.addProperty("reason",e.toString());save("needs_attention");}throw e;}}
    private boolean step(PilotController c)throws Exception {
        var mc=Actions.mc();
        if(!initialized){
            var rules=ServerRules.snapshot().getAsJsonObject("deathRecovery");if(rules==null||!rules.has("safeHome"))throw new IllegalStateException("No verified safe home has been configured");mapReturn=WaypointAccess.available();
            home=mapReturn?Jsons.obj("dimension","allthemodium:mining","x",387.5,"y",253.1,"z",-62.5):rules.getAsJsonObject("safeHome").deepCopy();
            if(!mapReturn){new Travel(Jsons.obj("command","home","expected",home));SurvivalCommands.validate("home",ServerRules.snapshot());}
            var priorFile=c.directory.resolve("last-safe-home.json");JsonObject prior=Files.isRegularFile(priorFile)?JsonParser.parseString(Files.readString(priorFile,StandardCharsets.UTF_8)).getAsJsonObject():null;
            boolean continuing=resume||(!incident.isEmpty()&&prior!=null&&incident.equals(Jsons.str(prior,"recoveryIncident","")));
            if(continuing){if(prior==null)throw new IllegalStateException("No saved safe-home return exists");report=prior;if(!home.equals(report.getAsJsonObject("home")))throw new IllegalStateException("Configured safe home differs from the saved return");}
            else report=Jsons.obj("startedAt",Instant.now().toString(),"recoveryIncident",incident,"home",home,"homeIntent",false,"origin",Jsons.obj("dimension",mc.level.dimension().location().toString(),"x",mc.player.getX(),"y",mc.player.getY(),"z",mc.player.getZ()));
            Actions.closeMenu();initialized=true;
            if(nearHome()){
                if(mc.player.getAbilities().flying&&mc.player.getY()>=Jsons.decimal(home,"y",0)+6){child=null;phase=4;save("healing_at_safe_hover");}
                else{child=hoverHere();phase=3;save("parking_at_home");}
            // Authorized native map travel can leave danger immediately. Activate and
            // verify flight after reaching the supported factory floor, not in the void.
            }else if(mapReturn||(continuing&&Jsons.bool(report,"homeIntent",false)))returnHome();
            else {child=hoverHere();phase=1;save("escaping_ground");}
        }
        if(phase==1){
            try{if(!child.tick(c))return false;}catch(IllegalStateException e){report.addProperty("initialHoverUnavailable",e.getMessage());Actions.releaseControls();}
            returnHome();return false;
        }
        if(phase==2){if(!child.tick(c))return false;Actions.releaseControls();if(!nearHome())throw new IllegalStateException("Return did not reach the configured safe home");child=hoverHere();phase=3;save("parking_at_home");return false;}
        if(phase==3){if(!child.tick(c))return false;Actions.releaseControls();if(!nearHome()||!mc.player.getAbilities().flying)throw new IllegalStateException("Safe-home hover was not verified");child=null;phase=4;save("healing_at_safe_hover");return false;}
        if(phase==4){
            if(!nearHome()||!mc.player.getAbilities().flying)throw new IllegalStateException("Safe healing position changed");
            var material=PlanModel.Material.item("minecraft:golden_apple");
            if(child!=null){
                if(!child.tick(c))return false;Actions.releaseControls();child=null;
                if(WorldView.count(material)!=appleBefore-1)throw new IllegalStateException("Golden-apple consumption was not confirmed; do not repeat blindly");
                healWait=100;save("golden_apple_consumed_verified");return false;
            }
            if(mc.player.getHealth()>=14){report.addProperty("healthAfter",mc.player.getHealth());report.addProperty("goldenApplesConsumed",apples);save("completed_safe_hover");return true;}
            if(healWait-->0)return false;
            if(apples>=3||WorldView.count(material)<1){report.addProperty("healthAfter",mc.player.getHealth());report.addProperty("healingIncomplete",true);save("completed_safe_hover");return true;}
            if(apples==0)report.addProperty("healthBeforeHealing",mc.player.getHealth());
            int slot=-1;for(int i=0;i<36;i++)if(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mc.player.getInventory().getItem(i).getItem()).toString().equals("minecraft:golden_apple")){slot=i;break;}
            if(slot>=9){Actions.click(mc.player.inventoryMenu.containerId,slot,8,net.minecraft.world.inventory.ClickType.SWAP);slot=8;}
            Actions.select(slot);appleBefore=WorldView.count(material);apples++;report.addProperty("goldenAppleIntents",apples);save("golden_apple_use_intent");
            child=PilotJobs.create(Jsons.obj("type","use_item","ticks",40));return false;
        }
        throw new IllegalStateException("Invalid safe-home phase");
    }
    public void cancel(){if(child!=null)child.cancel();Actions.releaseControls();if(report!=null)try{save("interrupted_needs_replan");}catch(Exception ignored){}}
}
