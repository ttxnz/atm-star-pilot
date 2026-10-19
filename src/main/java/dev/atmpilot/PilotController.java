package dev.atmpilot;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class PilotController {
    public interface Job {
        boolean tick(PilotController controller) throws Exception;
        default void cancel() {}
        default String description() { return getClass().getSimpleName(); }
    }
    public record PlanningInput(List<PlanModel.Recipe> recipes,Map<PlanModel.Material,Long> stock,Set<PlanModel.Material> stations) {}
    private final Deque<Job> queue=new ArrayDeque<>();
    private Job active;
    private String id="",message="Idle",state="idle",dimension="";
    private long ticks,jobTicks;
    private final ExecutorService logWriter=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ATM-Pilot-Journal");t.setDaemon(true);return t;});
    public final Map<String,JsonObject> observedContainers=new LinkedHashMap<>();
    public final Path directory=FMLPaths.CONFIGDIR.get().resolve("atmpilot");

    public JsonObject status() { return Jsons.obj("id",id,"state",state,"message",message,"active",active==null?null:active.description(),"queued",queue.size(),"ticks",ticks); }
    public boolean running() { return active!=null||!queue.isEmpty(); }
    public long ticks() { return ticks; }
    public String start(List<Job> jobs) {
        if(dev.atmpilot.vanilla.VanillaRun.selected())throw new IllegalStateException("Leave vanilla mode before dispatching ATM workflows");
        Actions.mc(); if(running()) throw new IllegalStateException("A job is already running; stop it before replacing it");
        if(jobs.isEmpty()||jobs.size()>4096) throw new IllegalArgumentException("Job list must contain 1..4096 actions");
        if(!Minecraft.getInstance().player.containerMenu.getCarried().isEmpty()) throw new IllegalStateException("Clear carried stack before starting");
        ManualControlGate.require(Jsons.bool(SurvivalReflex.status(),"manualHold",false)||Jsons.bool(DeathRecovery.status(),"manualHold",false),jobs.getFirst());
        id=UUID.randomUUID().toString(); queue.addAll(jobs); state="running"; message="Starting";
        dimension=Minecraft.getInstance().level.dimension().location().toString();SurvivalReflex.started(this); journal("started"); return id;
    }
    public void stop(String reason) {
        if(dev.atmpilot.vanilla.VanillaRun.selected()){dev.atmpilot.vanilla.VanillaRun.stop(reason);return;}
        RealtimeCombat.cancel();
        try{DeathRecovery.stopped(reason);}catch(Exception e){PilotMod.LOG.warn("Cannot update recovery stop checkpoint",e);}
        if(active!=null) active.cancel(); active=null; queue.clear(); Actions.releaseControls();
        state="stopped"; message=reason; journal("stopped");
        SurvivalReflex.stopped(this,reason);
    }
    public void note(String text) { message=text; }
    public PlanningInput planningInput() {
        OptionalJei.require();
        if(!JeiBridge.ready()) throw new IllegalStateException("Wait for the live JEI catalog to finish indexing");
        Set<PlanModel.Material> stations=new HashSet<>();
        WorldView.scan(16).forEach(e->stations.add(PlanModel.Material.item(e.getAsJsonObject().get("id").getAsString())));
        return new PlanningInput(JeiBridge.entries().stream().map(JeiBridge.Entry::recipe).toList(),WorldView.inventory(),Set.copyOf(stations));
    }
    public static JsonObject plan(PlanningInput input,String item,long count,String components) {
        Path plugin=FMLPaths.CONFIGDIR.get().resolve("atmpilot/runtime/automation.jar");
        if(Files.isRegularFile(plugin)){
            Path snapshot=null;
            try{
                Path cache=plugin.getParent().resolve("cache");Files.createDirectories(cache);
                snapshot=Files.createTempFile(cache,"planner-",".jar");Files.copy(plugin,snapshot,StandardCopyOption.REPLACE_EXISTING);
                try(var loader=new java.net.URLClassLoader(new java.net.URL[]{snapshot.toUri().toURL()},PilotController.class.getClassLoader())){
                    Class<?> module=Class.forName("dev.atmpilot.runtime.AutomationPlugin",true,loader);
                    return (JsonObject)module.getMethod("plan",PlanningInput.class,String.class,long.class,String.class)
                        .invoke(null,input,item,count,components);
                }
            }catch(ReflectiveOperationException|java.io.IOException e){
                Throwable cause=e instanceof java.lang.reflect.InvocationTargetException&&e.getCause()!=null?e.getCause():e;
                throw new IllegalStateException("Automation planner module failed: "+cause,cause);
            }finally{if(snapshot!=null)try{Files.deleteIfExists(snapshot);}catch(java.io.IOException ignored){}}
        }
        return Jsons.plan(new DependencyPlanner(input.recipes).plan(new PlanModel.Material(item,components),count,input.stock,input.stations));
    }
    public void preTick(ClientTickEvent.Pre event) {
        ticks++;
        if(dev.atmpilot.vanilla.VanillaRun.selected()){dev.atmpilot.vanilla.VanillaRun.tick();return;}
        var mc=Minecraft.getInstance();
        boolean wasDefending=RealtimeCombat.active();
        if(RealtimeCombat.tick(this)){
            if(wasDefending&&!RealtimeCombat.active()&&active!=null&&running()){
                if(active instanceof ReflexResumable resumable){if(!resumable.resumeAfterLocalCombat())stop("Local defense completed; interrupted action needs checkpoint replan");}
                else if(!(active instanceof PilotJobs.Wait)&&!Set.of("Walk","Approach","Mine","Place","Blueprint","Move").contains(active.getClass().getSimpleName()))stop("Local defense completed; interrupted action needs checkpoint replan");
            }
            return;
        }
        if(!running())return;
        if(mc.player==null||mc.level==null) { stop("Disconnected");return; }
        try{ManualControlGate.require(Jsons.bool(SurvivalReflex.status(),"manualHold",false)||Jsons.bool(DeathRecovery.status(),"manualHold",false),active==null?queue.peekFirst():active);}
        catch(IllegalStateException hold){stop("Manual control is held; queued actions released");return;}
        Job pendingAction=active==null?queue.peekFirst():active;
        boolean resuming=pendingAction instanceof ManualResumeJob resume&&resume.explicitlyResumesManualControl();
        if((!mc.player.isAlive()&&!resuming)||(mc.player.isAlive()&&mc.player.getHealth()<=1&&!SurvivalReflex.active(this))) { stop("Low health or death; controls released");return; }
        if(!dimension.equals(mc.level.dimension().location().toString())&&!DeathRecovery.active(this)
            &&!(active!=null&&active.description().startsWith("Authorized survival travel: "))) { stop("Dimension changed; rescan before continuing");return; }
        try {
            if(active==null) { active=queue.removeFirst(); jobTicks=0; note(active.description()); journal("action_started"); }
            if(++jobTicks>72_000) throw new IllegalStateException("Action exceeded one-hour tick budget");
            if(active.tick(this)) {
                if(active.description().startsWith("Authorized survival travel: "))dimension=mc.level.dimension().location().toString();
                journal("action_completed"); active=null; Actions.releaseControls();
                if(queue.isEmpty()) { state="completed"; message="All queued actions verified"; journal("completed"); }
            }
        } catch(Exception|LinkageError e) {
            PilotMod.LOG.warn("ATM Pilot action stopped",e); stop(e.getClass().getSimpleName()+": "+e.getMessage());
        }
    }
    public void postTick(ClientTickEvent.Post event) {
        if(dev.atmpilot.vanilla.VanillaRun.selected()){
            if(PilotMod.STOP.consumeClick())dev.atmpilot.vanilla.VanillaRun.stop("F8 emergency stop");
            if(PilotMod.OPEN.consumeClick()){dev.atmpilot.vanilla.VanillaRun.stop("Control panel opened");Minecraft.getInstance().setScreen(new PilotScreen(this));}
            return;
        }
        DeathRecovery.observe(this);
        OptionalJei.tick();
        if(PilotMod.STOP.consumeClick()) stop("F8 emergency stop");
        if(PilotMod.OPEN.consumeClick()) { if(running()) stop("Control panel opened"); Minecraft.getInstance().setScreen(new PilotScreen(this)); }
        SurvivalReflex.tick(this);
    }
    public void remember(String location) {
        var mc=Actions.mc(); var menu=WorldView.state(this).getAsJsonObject("menu");
        JsonObject record=Jsons.obj("dimension",mc.level.dimension().location().toString(),"seenAt",Instant.now().toString(),"menu",menu);
        observedContainers.put(location,record); journal("container_observed");
    }
    public void savePlan(JsonObject plan) {
        logWriter.execute(()->{ try { Files.createDirectories(directory); Files.writeString(directory.resolve("last-plan.json"),Jsons.GSON.toJson(plan),StandardCharsets.UTF_8); }
            catch(Exception e) { PilotMod.LOG.warn("Cannot save plan",e); } });
    }
    private void journal(String event) {
        String line=Jsons.obj("time",Instant.now().toString(),"event",event,"job",id,"state",state,"message",message).toString()+"\n";
        logWriter.execute(()->{ try { Files.createDirectories(directory); Files.writeString(directory.resolve("events.jsonl"),line,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
            catch(Exception e) { PilotMod.LOG.warn("Cannot write automation journal",e); } });
    }
}
