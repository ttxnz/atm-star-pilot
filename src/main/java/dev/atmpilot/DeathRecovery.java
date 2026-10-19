package dev.atmpilot;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.items.IItemHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Persistent death checkpoint and normal respawn. World recovery lives in the hot-loadable module. */
public final class DeathRecovery {
    private static JsonObject pending,lastAlive;
    private static boolean loaded,deadSeen,manualHold,respawnSent,recoveryDeathRisk;
    private static long deathTick,lastSample;
    private static String recoveryId="";
    private DeathRecovery(){}
    private static Path file(){return PilotMod.CONTROLLER.directory.resolve("recovery-state.json");}
    public static JsonObject inventorySnapshot(){
        var mc=Actions.mc();JsonArray inventory=new JsonArray(),curios=new JsonArray();
        for(int i=0;i<mc.player.getInventory().getContainerSize();i++){
            var stack=mc.player.getInventory().getItem(i);if(!stack.isEmpty()){var entry=WorldView.stack(stack);entry.addProperty("slot",i);inventory.add(entry);}
        }
        try{
            Class<?> api=Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Optional<?> handler=(Optional<?>)api.getMethod("getCuriosInventory",LivingEntity.class).invoke(null,mc.player);
            if(handler.isPresent()){
                IItemHandler items=(IItemHandler)Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler").getMethod("getEquippedCurios").invoke(handler.get());
                for(int i=0;i<items.getSlots();i++)if(!items.getStackInSlot(i).isEmpty()){var value=WorldView.stack(items.getStackInSlot(i));value.addProperty("curioSlot",i);curios.add(value);}
            }
        }catch(ClassNotFoundException absent){/* Optional on vanilla-only clients. */}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot snapshot equipped curios for recovery",e);}
        JsonArray craftingInputs=new JsonArray();var menu=mc.player.containerMenu;
        int gridSize=menu instanceof net.minecraft.world.inventory.CraftingMenu?9:menu instanceof net.minecraft.world.inventory.InventoryMenu?4:0;
        for(int i=1;i<=gridSize;i++)if(!menu.getSlot(i).getItem().isEmpty())craftingInputs.add(WorldView.stack(menu.getSlot(i).getItem()));
        return Jsons.obj("observedAt",Instant.now().toString(),"playerUuid",mc.player.getUUID().toString(),"dimension",mc.level.dimension().location().toString(),
            "x",mc.player.getX(),"y",mc.player.getY(),"z",mc.player.getZ(),"inventory",inventory,"curios",curios,"craftingInputs",craftingInputs,"carried",WorldView.stack(menu.getCarried()));
    }
    public static JsonObject status(){
        if(pending==null)return Jsons.obj("state","watching","hasLivingInventorySnapshot",lastAlive!=null,"manualHold",manualHold);
        JsonObject result=pending.deepCopy();result.remove("before");result.remove("after");result.addProperty("manualHold",manualHold);return result;
    }
    public static boolean active(PilotController controller){return !recoveryId.isEmpty()&&Jsons.str(controller.status(),"id","").equals(recoveryId);}
    private static void write(){
        if(pending==null)return;
        pending.addProperty("updatedAt",Instant.now().toString());Path target=file(),temporary=target.resolveSibling("recovery-state.tmp");
        try{Files.writeString(temporary,Jsons.GSON.toJson(pending),StandardCharsets.UTF_8);Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            if(pending.has("recordedAt")){Path history=PilotMod.CONTROLLER.directory.resolve("recovery-history");Files.createDirectories(history);Files.writeString(history.resolve(Jsons.str(pending,"recordedAt","").replaceAll("[^A-Za-z0-9._-]","_")+".json"),Jsons.GSON.toJson(pending),StandardCharsets.UTF_8);}}
        catch(Exception e){throw new IllegalStateException("Cannot persist death recovery checkpoint",e);}
    }
    public static void record(String field,JsonElement value){if(pending==null)throw new IllegalStateException("No recorded death to recover");pending.add(field,value.deepCopy());write();}
    public static void phase(String state){if(pending!=null){
        if(state.equals("acknowledged")||(state.equals("resume_requested")&&!pending.has("recordedAt"))){
            if(state.equals("acknowledged")&&!Jsons.str(pending,"state","").equals("recovered_needs_replan"))throw new IllegalStateException("Only a verified completed recovery may be acknowledged");
            pending.addProperty("replanAcknowledgedAt",Instant.now().toString());write();pending=null;manualHold=false;recoveryId="";lastAlive=inventorySnapshot();
            try{Files.writeString(file(),Jsons.GSON.toJson(Jsons.obj("version",1,"playerUuid",Actions.mc().player.getUUID().toString(),"state","watching","manualHold",false)),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("Cannot persist recovery acknowledgement",e);}return;
        }
        if(state.equals("resume_requested")){
            if(!Jsons.str(pending,"playerUuid","").equals(Actions.mc().player.getUUID().toString()))throw new IllegalStateException("Recovery checkpoint belongs to another player");
            manualHold=false;pending.addProperty("manualHold",false);respawnSent=false;
            state=Actions.mc().player.isAlive()?"respawning":"waiting_respawn";
        }
        pending.addProperty("state",state);write();
    }}
    public static void finished(JsonObject after,JsonObject missing){
        if(pending==null)throw new IllegalStateException("No pending recovery");
        pending.add("after",after.deepCopy());pending.add("missing",missing.deepCopy());
        pending.addProperty("state",!Jsons.bool(pending,"baselineAvailable",false)?"recovered_unverified_inventory":missing.isEmpty()?"recovered_needs_replan":"recovered_with_missing_items");
        pending.addProperty("needsReplan",true);recoveryId="";manualHold=true;write();
    }
    public static void manualInput(){
        SurvivalReflex.manualInput();
        manualHold=true;
        if(pending==null){var mc=Minecraft.getInstance();if(mc.player==null)return;pending=Jsons.obj("version",1,"playerUuid",mc.player.getUUID().toString(),"state","manual_hold_no_death","heldAt",Instant.now().toString());}
        else pending.addProperty("state","interrupted_by_player");
        pending.addProperty("manualHold",true);try{write();}catch(Exception e){PilotMod.LOG.warn("Cannot persist manual recovery interruption",e);}
    }
    public static void stopped(String reason){
        if(reason.startsWith("Manual")||reason.startsWith("F8")||reason.equals("Stopped by controller")||reason.equals("Control panel opened"))manualInput();
        else if(pending==null)return;
        else if(!recoveryId.isEmpty()&&!reason.equals("Death recorded for recovery")){
            if(reason.equals("Low health or death; controls released"))recoveryDeathRisk=true;
            pending.addProperty("state","needs_attention");pending.addProperty("reason",reason);manualHold=true;write();
        }
        recoveryId="";
    }
    public static void observe(PilotController controller){
        var mc=Minecraft.getInstance();if(mc.player==null||mc.level==null)return;
        try{
            if(!loaded){
                loaded=true;
                if(Files.isRegularFile(file())&&Files.size(file())<8_000_000){
                    JsonObject saved=JsonParser.parseString(Files.readString(file(),StandardCharsets.UTF_8)).getAsJsonObject();
                    if(Jsons.str(saved,"playerUuid","").equals(mc.player.getUUID().toString())){
                        pending=saved;manualHold=Jsons.bool(saved,"manualHold",false);deadSeen=!mc.player.isAlive();
                        if(Set.of("recovering","returning","locating","retrieving","escaping_after_retrieval").contains(Jsons.str(saved,"state","")))phase("respawning");
                        if(Jsons.str(saved,"state","").equals("watching"))pending=null;
                    }
                }
            }
            boolean alive=mc.player.isAlive();
            if(alive&&controller.ticks()-lastSample>=20){lastSample=controller.ticks();if(recoveryId.isEmpty())lastAlive=inventorySnapshot();}
            if(alive&&!deadSeen&&pending==null)return;
            JsonObject rules=ServerRules.snapshot().getAsJsonObject("deathRecovery");if(rules==null||!Jsons.bool(rules,"enabled",false))return;
            if(!alive){
                if(!deadSeen){
                    boolean secondDeath=!recoveryId.isEmpty()||recoveryDeathRisk;recoveryDeathRisk=false;JsonObject interrupted=controller.status();
                    int cycles=1;if(pending!=null&&pending.has("recordedAt")){long seconds=java.time.Duration.between(Instant.parse(Jsons.str(pending,"recordedAt","")),Instant.now()).getSeconds();if(seconds>=0&&seconds<Math.max(30,Math.min(600,Jsons.integer(rules,"recentDeathWindowSeconds",120))))cycles=Jsons.integer(pending,"recentRecoveryCycles",1)+1;}
                    boolean preserveHold=RecoveryControl.holdAfterNewDeath(manualHold,pending==null?"":Jsons.str(pending,"state",""),cycles,Jsons.integer(rules,"maxReturnAttempts",2));
                    if(secondDeath){pending.add("secondaryDeath",Jsons.obj("dimension",mc.level.dimension().location().toString(),"x",mc.player.getX(),"y",mc.player.getY(),"z",mc.player.getZ()));manualHold=true;}
                    else{
                        pending=Jsons.obj("version",1,"playerUuid",mc.player.getUUID().toString(),"state","waiting_respawn","recordedAt",Instant.now().toString(),
                            "dimension",mc.level.dimension().location().toString(),"x",mc.player.getX(),"y",mc.player.getY(),"z",mc.player.getZ(),
                            "before",lastAlive==null?inventorySnapshot():lastAlive.deepCopy(),"baselineAvailable",lastAlive!=null,"interruptedJob",interrupted,"backSent",false,"manualHold",preserveHold,"recentRecoveryCycles",cycles);
                        Path dispatch=controller.directory.resolve("last-dispatch.json");
                        if(Files.isRegularFile(dispatch)&&Files.size(dispatch)<2_100_000)pending.add("lastDispatch",JsonParser.parseString(Files.readString(dispatch,StandardCharsets.UTF_8)));
                        manualHold=preserveHold;if(preserveHold){pending.addProperty("state","needs_attention");pending.addProperty("reason","Manual hold or repeated deaths; resume only after inspecting a safer plan");}
                    }
                    controller.stop("Death recorded for recovery");deadSeen=true;deathTick=controller.ticks();respawnSent=false;write();
                    if(secondDeath){phase("needs_attention");return;}
                }
                if(!manualHold&&!respawnSent&&controller.ticks()-deathTick>=40&&mc.screen instanceof DeathScreen){
                    mc.player.respawn();mc.setScreen(null);respawnSent=true;phase("respawning");
                }
                return;
            }
            if(deadSeen){deadSeen=false;respawnSent=false;}
            if(pending==null||manualHold||controller.running())return;
            String state=Jsons.str(pending,"state","");
            if(state.equals("waiting_respawn")||state.equals("respawning")){
                JsonObject job=pending.deepCopy();job.addProperty("type","extension:recover");
                recoveryId=controller.start(List.of(PilotJobs.create(job)));phase("recovering");
            }
        }catch(Exception|LinkageError e){
            manualHold=true;recoveryId="";PilotMod.LOG.warn("Death recovery observer needs attention",e);
            if(pending!=null){pending.addProperty("state","needs_attention");pending.addProperty("reason",e.toString());try{write();}catch(Exception ignored){}}
        }
    }
}
