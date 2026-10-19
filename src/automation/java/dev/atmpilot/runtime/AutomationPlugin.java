package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.client.KeyMapping;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

/** Each call owns a fresh loader and a frozen JAR snapshot; future jobs see the updated module. */
public final class AutomationPlugin {
    public static JsonObject plan(PilotController.PlanningInput input,String item,long count,String components){
        JsonObject result=Jsons.plan(new FastPlanner(input.recipes()).plan(new PlanModel.Material(item,components),count,input.stock(),input.stations()));
        result.addProperty("planner","hot-module-v1");result.addProperty("strategy","bounded cycle-aware route estimate");
        result.add("catalog",JeiBridge.status());return result;
    }
    public static PilotController.Job create(JsonObject data,URLClassLoader loader,Path snapshot){
        String type=Jsons.str(data,"type","");PilotController.Job job;
        if(type.equals("extension:probe"))job=new Probe(Jsons.integer(data,"ticks",2),Jsons.str(data,"message","Runtime module v1"));
        else if(type.equals("extension:background_settings"))job=new BackgroundSettings(data);
        else if(type.equals("extension:open_backpack"))job=new OpenBackpack();
        else if(type.equals("extension:compass_open"))job=new CompassOpen();
        else if(type.equals("extension:survey"))job=new AreaSurvey(data);
        else if(type.equals("extension:flat_platform"))job=new FlatPlatform(data);
        else if(type.equals("extension:gadget"))job=new GadgetBatch(data);
        else if(type.equals("extension:surface_scan"))job=new SurfaceScan(data);
        else if(type.equals("extension:ore_acquire"))job=new OreAcquire(data);
        else if(type.equals("extension:mine_held"))job=new HeldToolMine(data);
        else if(type.equals("extension:ae_inspect"))job=new AeAccess.Inspect(data);
        else if(type.equals("extension:ae_open"))job=new AeAccess.Open(data);
        else if(type.equals("extension:ae_withdraw"))job=new AeAccess.Withdraw(data);
        else if(type.equals("extension:ae_deposit"))job=new AeAccess.Deposit(data);
        else if(type.equals("extension:ae_attach"))job=new AeAccess.Attach(data);
        else if(type.equals("extension:ae_clear_grid"))job=new AeAccess.ClearGrid();
        else if(type.equals("extension:ae_grid_take"))job=new AeGridTake(data);
        else if(type.equals("extension:warden_capture"))job=new ObservedEntityUse(data);
        else if(type.equals("extension:warden_name"))job=new ObservedEntityUse.Name(data);
        else if(type.equals("extension:air_combat"))job=new AirCombat(data);
        else if(type.equals("extension:computer_open"))job=new ComputerAccess.Open(data);
        else if(type.equals("extension:computer_inspect"))job=new ComputerAccess.Inspect();
        else if(type.equals("extension:computer_upload"))job=new ComputerAccess.Upload(data);
        else if(type.equals("extension:ae_native_inspect"))job=new AeNative.Inspect();
        else if(type.equals("extension:sfm_edit_program"))job=new SfmEditor(data);
        else if(type.equals("extension:sfm_inspect"))job=new SfmEditor.Inspect();
        else if(type.equals("extension:ae_encode"))job=new AeNative.Encode(data);
        else if(type.equals("extension:ae_processing_encode"))job=new AeProcessingEncode(data);
        else if(type.equals("extension:ae_reagent_filter"))job=new AeReagentFilter(data);
        else if(type.equals("extension:ae_inscriber_configure"))job=new AeInscriberConfigure(data);
        else if(type.equals("extension:ae_transform_batch"))job=new ContainedTransformBatch(data);
        else if(type.equals("extension:ae_request"))job=new AeNative.Request(data);
        else if(type.equals("extension:mek_recipe"))job=new MekRecipes(data);
        else if(type.equals("extension:mek_inspect"))job=new MekInspect();
        else if(type.equals("extension:mek_configure"))job=new MekConfigure(data);
        else if(type.equals("extension:furnace_top_io"))job=new FurnaceTopIo(data);
        else if(type.equals("extension:mek_batch"))job=new MekBatch(data);
        else if(type.equals("extension:botany_inspect"))job=new BotanyInspect(data);
        else if(type.equals("extension:titanium_inspect"))job=new TitaniumInspect();
        else if(type.equals("extension:titanium_addons"))job=new TitaniumAddons(data);
        else if(type.equals("extension:spawner_inspect"))job=new SpawnerInspect(data);
        else if(type.equals("extension:pipe_inspect"))job=new PipeAccess.Inspect(data);
        else if(type.equals("extension:pipe_configure"))job=new PipeAccess.Configure(data);
        else if(type.equals("extension:pipe_whitelist"))job=new PipeFilterAccess.Whitelist(data);
        else if(type.equals("extension:pipe_clear_plain_whitelist"))job=new PipeFilterAccess.ClearPlainWhitelist(data);
        else if(type.equals("extension:infusion_inspect"))job=new Infusion.Inspect(data);
        else if(type.equals("extension:infusion"))job=new Infusion.Run(data);
        else if(type.equals("extension:water_source"))job=new WaterSource(data);
        else if(type.equals("extension:contained_fluid_catalyst"))job=new ContainedFluidCatalyst(data);
        else if(type.equals("extension:entropy_freeze"))job=new EntropyFreeze(data);
        else if(type.equals("extension:bucket_ray"))job=new WaterSource.InspectRay();
        else if(type.equals("extension:fluix_batch"))job=new FluixBatch(data);
        else if(type.equals("extension:menu_probe"))job=new MenuProbe(data);
        else if(type.equals("extension:item_facts"))job=new ItemFacts(data);
        else if(type.equals("extension:collect_output"))job=new CollectOutput(data);
        else if(type.equals("extension:charger_inspect"))job=new ChargerBatch.Inspect(data);
        else if(type.equals("extension:charger_batch"))job=new ChargerBatch.Run(data);
        else if(type.equals("extension:squeezer_inspect"))job=new SqueezerInspect(data);
        else if(type.equals("extension:squeezer_press"))job=new SqueezerPress(data);
        else if(type.equals("extension:fluid_tank_inspect"))job=new SqueezerInspect.Tank();
        else if(type.equals("extension:bee_spawning_inspect"))job=new BeeSpawningInspect(data);
        else if(type.equals("extension:bee_nest_inspect"))job=new BeeTools.NestInspect(data);
        else if(type.equals("extension:bee_recipes"))job=new BeeTools.Recipes(data);
        else if(type.equals("extension:bee_capture"))job=new BeeCapture(data);
        else if(type.equals("extension:bee_watch"))job=new BeeWatch(data);
        else if(type.equals("extension:shrink_configure"))job=new ShrinkTools.Configure(data);
        else if(type.equals("extension:animal_inspect"))job=new ShrinkTools.Inspect(data);
        else if(type.equals("extension:animal_capture"))job=new ShrinkTools.Capture(data);
        else if(type.equals("extension:combat"))job=new MeleeFighter(Jsons.integer(data,"entityId",-1),UUID.fromString(Jsons.str(data,"uuid","")),Jsons.str(data,"expectedType",""));
        else if(type.equals("extension:live_inspect"))job=new LiveInspect(data);
        else if(type.equals("extension:command_suggestions"))job=new CommandSuggestions(data);
        else if(type.equals("extension:chunks_open"))job=new ChunkAccess.Open(data);
        else if(type.equals("extension:chunks_inspect"))job=new ChunkAccess.Inspect(data);
        else if(type.equals("extension:chunks_claim_load"))job=new ChunkAccess.ClaimLoad(data);
        else if(type.equals("extension:open_face"))job=new OpenFace(data);
        else if(type.equals("extension:use_sneaking"))job=new SneakingUse(data);
        else if(type.equals("extension:flight_status"))job=new Flight.Inspect();
        else if(type.equals("extension:flight_toggle"))job=new Flight.Toggle(data);
        else if(type.equals("extension:fly"))job=new Flight.Fly(data);
        else if(type.equals("extension:background"))job=new Flight.Background();
        else if(type.equals("extension:recover"))job=new Recover(data);
        else if(type.equals("extension:travel"))job=new Travel(data);
        else if(type.equals("extension:safe_home"))job=new SafeHome(data);
        else if(type.equals("extension:pad_travel"))job=new PadTravel(data);
        else if(type.equals("extension:portal_travel"))job=new PortalTravel(data);
        else if(type.equals("extension:waystone_open"))job=new WaystoneAccess.Open(data);
        else if(type.equals("extension:waystone_inspect"))job=new WaystoneAccess.Inspect();
        else if(type.equals("extension:waystone_travel"))job=new WaystoneAccess.Travel(data);
        else if(type.equals("extension:waypoints_inspect"))job=new WaypointAccess.Inspect(data);
        else if(type.equals("extension:waypoint_add"))job=new WaypointAccess.Add(data);
        else if(type.equals("extension:waypoint_travel"))job=new WaypointAccess.Travel(data);
        else if(type.equals("extension:recovery_resume"))job=new RecoveryResume(data);
        else if(type.equals("extension:recovery_acknowledge"))job=new RecoveryAcknowledge();
        else if(type.equals("extension:sequence")){
            var steps=data.getAsJsonArray("steps");if(steps==null||steps.isEmpty()||steps.size()>4096)throw new IllegalArgumentException("Expected 1..4096 steps");
            List<PilotController.Job> children=new ArrayList<>();
            for(var element:steps){JsonObject step=element.getAsJsonObject();
                if(Jsons.str(step,"type","").startsWith("extension:"))throw new IllegalArgumentException("Nested extension jobs require an explicit adapter");
                children.add(PilotJobs.create(step));
            }
            job=new Sequence(children);
        }else throw new IllegalArgumentException("Unknown automation extension: "+type);
        return new Managed(job,loader,snapshot);
    }
    private static final class Managed implements PilotController.Job,ManualResumeJob,ReflexResumable {
        private final PilotController.Job child;private final URLClassLoader loader;private final Path snapshot;private boolean closed;
        Managed(PilotController.Job child,URLClassLoader loader,Path snapshot){this.child=child;this.loader=loader;this.snapshot=snapshot;}
        public String description(){return child.description();}
        public boolean explicitlyResumesManualControl(){return child instanceof RecoveryResume;}
        public boolean resumeAfterLocalCombat(){return child instanceof ReflexResumable resumable&&resumable.resumeAfterLocalCombat();}
        public boolean tick(PilotController controller)throws Exception{
            try{boolean done=child.tick(controller);if(done)close();return done;}catch(Exception|LinkageError e){close();throw e;}
        }
        public void cancel(){try{child.cancel();}finally{close();}}
        private void close(){if(closed)return;closed=true;try{loader.close();Files.deleteIfExists(snapshot);}catch(Exception e){PilotMod.LOG.warn("Cannot close runtime module snapshot",e);}}
    }
    private static final class Probe implements PilotController.Job {
        private int remaining;private final String message;
        Probe(int ticks,String message){if(ticks<1||ticks>200)throw new IllegalArgumentException("Probe ticks must be 1..200");remaining=ticks;this.message=message;}
        public String description(){return message;}
        public boolean tick(PilotController controller){controller.note(message);return --remaining<=0;}
    }
    private static final class RecoveryResume implements PilotController.Job {
        private boolean respawnSent;private int ticks;private final boolean prepareOnly;
        RecoveryResume(JsonObject data){prepareOnly=Jsons.bool(data,"prepareOnly",false);}
        public String description(){return "Resume the recorded recovery after inspecting its checkpoint";}
        public boolean tick(PilotController controller){
            var mc=Actions.mc();
            if(!mc.player.isAlive()){
                if(++ticks>200)throw new IllegalStateException("Native respawn was not acknowledged; inspect recovery checkpoint");
                if(!respawnSent&&mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen){mc.player.respawn();mc.setScreen(null);respawnSent=true;}
                return false;
            }
            String state=Jsons.str(DeathRecovery.status(),"state","");
            if(state.equals("recovered_needs_replan"))DeathRecovery.phase("acknowledged");
            else if(!state.equals("watching")){DeathRecovery.phase("resume_requested");if(prepareOnly)DeathRecovery.phase("preparing_recovery");}
            SurvivalReflex.started(controller);return true;
        }
    }
    private static final class CompassOpen implements PilotController.Job {
        private int ticks;
        public String description(){return "Open the held natural-biome compass through its normal item use";}
        public boolean tick(PilotController c){var mc=Actions.mc();if(mc.screen!=null&&mc.screen.getClass().getName().equals("com.chaosthedude.naturescompass.gui.NaturesCompassScreen"))return true;if(ticks++==0){if(mc.screen!=null||!mc.player.containerMenu.getCarried().isEmpty()||!net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString().equals("naturescompass:naturescompass"))throw new IllegalStateException("Close menus and select the observed natural compass");mc.gameMode.useItem(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND);}if(ticks>60)throw new IllegalStateException("Compass GUI did not open");return false;}
    }
    private static final class LiveInspect implements PilotController.Job {
        private final int radius;LiveInspect(JsonObject data){radius=Jsons.integer(data,"radius",16);}
        public boolean tick(PilotController controller)throws Exception{
            Files.writeString(controller.directory.resolve("last-live-inspection.json"),Jsons.GSON.toJson(LiveTelemetry.read(controller,radius)),java.nio.charset.StandardCharsets.UTF_8);return true;
        }
        public String description(){return "Read compact live local controls and entities";}
    }
    private static final class RecoveryAcknowledge implements PilotController.Job {
        public String description(){return "Acknowledge verified recovery after replanning; retain its archived evidence";}
        public boolean tick(PilotController controller){DeathRecovery.phase("acknowledged");return true;}
    }
    private static final class SneakingUse implements PilotController.Job {
        private final net.minecraft.core.BlockPos position;private final net.minecraft.core.Direction face;private final String item,expected;private int elapsed;private boolean sent;
        SneakingUse(JsonObject data){position=Actions.pos(data);face=net.minecraft.core.Direction.valueOf(Jsons.str(data,"face","up").toUpperCase(Locale.ROOT));item=Jsons.str(data,"item","");expected=Jsons.str(data,"expectedBlock","");}
        public String description(){return "Use held "+item+" while sneaking at "+position.toShortString();}
        public boolean tick(PilotController controller){
            var mc=Actions.mc();ServerRules.checkBuild(position);Actions.checkReach(position);
            String actual=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(position).getBlock()).toString();
            if(!expected.isEmpty()&&expected.equals(actual))return true;
            if(elapsed++==0){Actions.closeMenu();SneakControl.hold();return false;}
            if(elapsed<=5){SneakControl.hold();return false;}
            if(!sent){
                if(!item.isEmpty()&&!net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString().equals(item))throw new IllegalStateException("Held item changed before sneaking interaction");
                if(mc.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem)ServerRules.checkBuild(position.relative(face));
                Actions.useBlock(position,face);sent=true;return false;
            }
            if(expected.isEmpty()&&elapsed>10)return true;
            if(elapsed>100)throw new IllegalStateException("Sneaking interaction did not produce the expected block; inspect before retrying");return false;
        }
        public void cancel(){Actions.releaseControls();}
    }
    private static final class OpenFace implements PilotController.Job {
        private final net.minecraft.core.BlockPos position;private final net.minecraft.core.Direction face;
        private final boolean expectMenu;
        private final net.minecraft.world.phys.Vec3 point;private int elapsed,initial;
        OpenFace(JsonObject data){
            position=Actions.pos(data);face=net.minecraft.core.Direction.valueOf(Jsons.str(data,"face","up").toUpperCase(Locale.ROOT));
            expectMenu=Jsons.bool(data,"expectMenu",true);
            if(data.has("hitX")||data.has("hitY")||data.has("hitZ")){
                double x=Jsons.decimal(data,"hitX",.5),y=Jsons.decimal(data,"hitY",.5),z=Jsons.decimal(data,"hitZ",.5);
                for(double coordinate:new double[]{x,y,z})if(!Double.isFinite(coordinate)||coordinate<.05||coordinate>.95)
                    throw new IllegalArgumentException("Face offsets must be finite values within .05.. .95");
                switch(face){case DOWN->y=.01;case UP->y=.99;case WEST->x=.01;case EAST->x=.99;case NORTH->z=.01;case SOUTH->z=.99;}
                point=new net.minecraft.world.phys.Vec3(position.getX()+x,position.getY()+y,position.getZ()+z);
            }else point=null;
        }
        public String description(){return "Open a reachable block from its "+face+" face";}
        public boolean tick(PilotController controller){
            var mc=Actions.mc();
            if(elapsed++==0){
                Actions.closeMenu();initial=mc.player.containerMenu.containerId;
                if(point==null)Actions.useBlock(position,face);
                else{
                    ServerRules.checkBuild(position);ServerRules.checkOpen(position);Actions.checkReach(position);
                    if(mc.player.getEyePosition().distanceTo(point)>mc.player.blockInteractionRange())throw new IllegalStateException("Selected face point is outside ordinary reach");
                    var trace=mc.level.clip(new net.minecraft.world.level.ClipContext(mc.player.getEyePosition(),point,
                        net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,mc.player));
                    if(trace.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK||!trace.getBlockPos().equals(position))
                        throw new IllegalStateException("Selected face point is not visibly reachable: "+trace.getBlockPos());
                    Actions.lookAt(point);
                    var result=mc.gameMode.useItemOn(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(point,face,position,false));
                    if(result.consumesAction())mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
                return false;
            }
            if(!expectMenu&&elapsed>=10)return true;
            if(mc.screen!=null&&mc.player.containerMenu.containerId!=initial){controller.remember(mc.level.dimension().location()+"@"+position.toShortString());return true;}
            if(elapsed>100)throw new IllegalStateException("Block did not open a menu from the selected face");return false;
        }
    }
    private static final class OpenBackpack implements PilotController.Job {
        private int elapsed,initial;
        public String description(){return "Open personal backpack using its normal key binding";}
        public boolean tick(PilotController controller){
            var mc=Actions.mc();
            if(elapsed++==0){
                Actions.closeMenu();initial=mc.player.containerMenu.containerId;
                List<KeyMapping> matches=new ArrayList<>();
                for(var binding:mc.options.keyMappings){String name=binding.getName().toLowerCase(Locale.ROOT);
                    if(name.contains("backpack")&&name.contains("open")&&!name.contains("setting"))matches.add(binding);
                }
                if(matches.size()!=1)throw new IllegalStateException("Expected one backpack-open key binding; found "+matches.stream().map(KeyMapping::getName).toList());
                KeyMapping.click(matches.getFirst().getKey());return false;
            }
            if(mc.player.containerMenu.containerId!=initial&&mc.screen!=null
                &&mc.screen.getClass().getName().startsWith("net.p3pp3rf1y.sophisticatedbackpacks."))return true;
            if(elapsed>60)throw new IllegalStateException("Personal backpack did not open through its normal key binding");
            return false;
        }
    }
    private static final class Sequence implements PilotController.Job {
        private final List<PilotController.Job> children;private int index;
        Sequence(List<PilotController.Job> children){this.children=List.copyOf(children);}
        public String description(){return "Runtime sequence "+index+" / "+children.size();}
        public boolean tick(PilotController controller)throws Exception{
            if(index>=children.size())return true;
            controller.note(children.get(index).description());
            if(children.get(index).tick(controller)){index++;Actions.releaseControls();}
            return index>=children.size();
        }
        public void cancel(){if(index<children.size())children.get(index).cancel();}
    }
}
