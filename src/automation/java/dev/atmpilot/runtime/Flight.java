package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.IItemHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Uses only flight already granted by the game; never writes abilities or position. */
public final class Flight {
    private Flight(){}
    public static final class Toggle implements PilotController.Job {
        private final boolean desired;private int elapsed;private boolean initialized;
        public Toggle(JsonObject data){desired=Jsons.bool(data,"flying",Jsons.bool(data,"enabled",true));}
        public String description(){return "Toggle existing flight using normal double Space";}
        public boolean tick(PilotController c){
            var mc=Actions.mc();if(mc.screen!=null||!Jsons.bool(ServerRules.snapshot(),"allowExistingSurvivalFlight",false))throw new IllegalStateException("Close the screen and use only the existing permitted flight");
            if(mc.player.getAbilities().flying==desired){Actions.releaseControls();return true;}
            if(!initialized){initialized=true;if(!desired){boolean support=false;BlockPos foot=mc.player.blockPosition();
                for(int i=0;i<=3;i++){BlockPos p=foot.below(i);if(!LoadedWorld.has(p))throw new IllegalStateException("Flight-off landing is not loaded");
                    if(!mc.level.getBlockState(p).getFluidState().isEmpty())throw new IllegalStateException("Flight-off landing contains fluid");
                    if(!mc.level.getBlockState(p).getCollisionShape(mc.level,p).isEmpty()){support=true;break;}}
                if(!support)throw new IllegalStateException("Move close to a dry landing before disabling flight");}}
            if(elapsed>=20)throw new IllegalStateException("Normal double Space did not reach the requested flight state");
            mc.options.keyShift.setDown(false);mc.options.keyJump.setDown(elapsed==0||elapsed==3);elapsed++;return false;
        }
        public void cancel(){Actions.releaseControls();}
    }
    public static final class Background implements PilotController.Job {
        public String description(){return "Allow the multiplayer automation to run while the game window is unfocused";}
        public boolean tick(PilotController controller)throws Exception{
            var mc=Actions.mc();
            var report=Jsons.obj("observedAt",Instant.now().toString(),"windowActive",mc.isWindowActive(),"previousPauseOnLostFocus",mc.options.pauseOnLostFocus,"pauseOnLostFocus",false,
                "scope","Current game process only; other controls and explicit pause still apply");
            Files.writeString(controller.directory.resolve("background-mode.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);
            mc.options.pauseOnLostFocus=false;return true;
        }
    }
    public static final class Fly implements PilotController.Job,ReflexResumable {
        private final FlightSteering steering=new FlightSteering();
        private FlightControls controls=new FlightControls();
        private BlockPos destination;private final int configuredCruise;private final boolean hover;private int elapsed,phase,activation,stableTicks;private double cruise;private boolean initialized,direct;private Vec3 started;
        private List<BlockPos> detour=List.of();private int detourIndex,resumePhase,detours,progressTicks;private double bestProgress=Double.POSITIVE_INFINITY;
        private PilotController.Job detourWalk;
        public Fly(JsonObject data){destination=Actions.pos(data);configuredCruise=Jsons.integer(data,"cruiseY",-4096);hover=Jsons.bool(data,"hover",false);}
        public void retarget(BlockPos next){
            if(next.distSqr(destination)>64||next.getY()>cruise-3)throw new IllegalStateException("Moving landing left the verified local flight segment");
            destination=next;stableTicks=0;if(phase==2)phase=1;
        }
        public boolean resumeAfterLocalCombat(){if(detourWalk!=null)detourWalk.cancel();detourWalk=null;phase=0;activation=0;initialized=false;stableTicks=0;detour=List.of();detours=0;resetProgress();steering.reset();controls=new FlightControls();Actions.releaseControls();return true;}
        public String description(){return "Fly using existing ability to "+destination.toShortString()+" (phase "+phase+")";}
        private void landing(){
            var mc=Actions.mc();
            if(!LoadedWorld.has(destination)||!LoadedWorld.has(destination.above()))throw new IllegalStateException("Flight landing chunk is not loaded");
            if((!hover&&!mc.level.getBlockState(destination.below()).isFaceSturdy(mc.level,destination.below(),Direction.UP))
                ||!mc.level.getBlockState(destination).getCollisionShape(mc.level,destination).isEmpty()
                ||!mc.level.getBlockState(destination.above()).getCollisionShape(mc.level,destination.above()).isEmpty()
                ||!mc.level.getBlockState(destination).getFluidState().isEmpty())throw new IllegalStateException("Flight destination must be clear and dry, with support when landing");
        }
        private boolean clear(Vec3 delta){return segmentClear(Actions.mc().player.position(),Actions.mc().player.position().add(delta));}
        private boolean segmentClear(Vec3 from,Vec3 to){
            var mc=Actions.mc();var body=mc.player.getBoundingBox().move(from.subtract(mc.player.position()));
            var delta=to.subtract(from);int steps=Math.max(1,(int)Math.ceil(delta.length()/.2));
            for(int i=0;i<=steps;i++){
                // Test the swept body in short slices. A whole diagonal bounding box
                // falsely included nearby machines the player never actually crossed.
                AABB box=body.move(delta.scale((double)i/steps)).deflate(1e-5);
                for(BlockPos p:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(box.maxX,box.maxY,box.maxZ)))
                    if(!LoadedWorld.has(p)||!mc.level.getFluidState(p).isEmpty())return false;
                if(!mc.level.noCollision(mc.player,box))return false;
            }
            return true;
        }
        private void resetProgress(){bestProgress=Double.POSITIVE_INFINITY;progressTicks=0;}
        private boolean stalled(double remaining){
            if(remaining<bestProgress-.04){bestProgress=remaining;progressTicks=0;return false;}
            return ++progressTicks>=60;
        }
        private void planDetour(PilotController c){
            var player=Actions.mc().player;BlockPos start=player.blockPosition(),goal;
            boolean localDestination=player.position().distanceTo(Vec3.atBottomCenterOf(destination))<=32;
            // Near machinery, the cruise point itself may be inside a platform.
            // Search all the way to the clear maintenance point instead of requiring
            // that unreachable intermediate altitude before allowing descent.
            if(localDestination)goal=destination;
            else if(phase==0)goal=new BlockPos(start.getX(),(int)Math.round(cruise),start.getZ());
            else if(phase==1){
                Vec3 delta=Vec3.atBottomCenterOf(destination).subtract(player.position());double distance=Math.hypot(delta.x,delta.z),step=Math.min(8,distance);
                goal=BlockPos.containing(player.getX()+delta.x/Math.max(.001,distance)*step,cruise,player.getZ()+delta.z/Math.max(.001,distance)*step);
            }else goal=destination;
            if(++detours>3)throw new IllegalStateException("Flight made no safe progress after bounded local detours; controls released on site");
            var first=new FlightPathfinder.Cell(start.getX(),start.getY(),start.getZ());
            var last=new FlightPathfinder.Cell(goal.getX(),goal.getY(),goal.getZ());
            Vec3 actual=player.position();Map<FlightPathfinder.Cell,Boolean> clearCells=new HashMap<>();
            var route=FlightPathfinder.find(first,last,(a,b)->{
                Vec3 to=new Vec3(b.x()+.5,b.y(),b.z()+.5);
                if(!clearCells.computeIfAbsent(b,cell->segmentClear(to,to)))return false;
                Vec3 from=a.equals(first)?actual:new Vec3(a.x()+.5,a.y(),a.z()+.5);
                return segmentClear(from,to);
            },4096);
            if(route.isEmpty())throw new IllegalStateException("No loaded, dry flight detour around the obstruction; controls released on site");
            detour=new ArrayList<>();for(int i=1;i<route.size();i++){
                var p=route.get(i);
                if(i+1<route.size()){
                    var before=route.get(i-1);var after=route.get(i+1);
                    if(p.x()-before.x()==after.x()-p.x()&&p.y()-before.y()==after.y()-p.y()&&p.z()-before.z()==after.z()-p.z())continue;
                }
                detour.add(new BlockPos(p.x(),p.y(),p.z()));
            }
            if(detour.isEmpty())throw new IllegalStateException("Flight has stalled at its target; inspect actual position before continuing");
            resumePhase=localDestination?3:phase;phase=4;detourIndex=0;resetProgress();steering.reset();Actions.releaseControls();
            c.note("Flight found a loaded dry detour of "+detour.size()+" cells");
        }
        private void advanceDetour(){
            detourWalk=null;if(++detourIndex==detour.size()){phase=resumePhase;detour=List.of();}
            resetProgress();steering.reset();Actions.releaseControls();
        }
        private boolean followDetour(PilotController c){
            var player=Actions.mc().player;var target=Vec3.atBottomCenterOf(detour.get(detourIndex));var delta=target.subtract(player.position());var velocity=player.getDeltaMovement();
            if(!segmentClear(player.position(),target))throw new IllegalStateException("Flight detour changed; controls released on site");
            if(stalled(delta.length()))throw new IllegalStateException("Flight detour stopped making progress; controls released on site");
            if(delta.length()<.23&&velocity.length()<.09){
                advanceDetour();return false;
            }
            FlightControls.horizontal(steering.horizontal(delta.x,delta.z,velocity.x,velocity.z,player.getYRot(),false,player.getAbilities().getFlyingSpeed()));
            controls.vertical(target.y);c.note(description()+" detour "+detourIndex+"/"+detour.size());return false;
        }
        private boolean settled(PilotController controller,boolean arrived){
            if(!arrived){stableTicks=0;return false;}
            Actions.releaseControls();if(++stableTicks<3)return false;
            var player=Actions.mc().player;
            try{Files.writeString(controller.directory.resolve("last-flight.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"state","completed_verified","ticks",elapsed,"direct",direct,"hover",hover,"detours",detours,"from",Jsons.obj("x",started.x,"y",started.y,"z",started.z),"to",Jsons.obj("x",player.getX(),"y",player.getY(),"z",player.getZ()),"destination",Jsons.obj("x",destination.getX(),"y",destination.getY(),"z",destination.getZ()),"maxYawStep",steering.maxYawStep())),StandardCharsets.UTF_8);}
            catch(java.io.IOException e){PilotMod.LOG.warn("Cannot save completed flight telemetry",e);}
            return true;
        }
        public boolean tick(PilotController controller){
            var mc=Actions.mc();var player=mc.player;
            if(mc.screen!=null)throw new IllegalStateException("Close the screen before flight");
            if(!Jsons.bool(ServerRules.snapshot(),"allowExistingSurvivalFlight",false))throw new IllegalStateException("Existing flight is not enabled in server rules");
            if(!initialized){
                if(LoadedWorld.has(destination))landing();
                if(player.position().distanceTo(Vec3.atBottomCenterOf(destination))>512)throw new IllegalArgumentException("Flight segment must be at most 512 blocks");
                started=player.position();var delta=Vec3.atBottomCenterOf(destination).subtract(started);
                direct=delta.length()<=12&&LoadedWorld.has(destination)&&clear(delta);
                cruise=configuredCruise==-4096?Math.max(Math.ceil(player.getY())+6,destination.getY()+6):configuredCruise;
                if(!direct&&(cruise<Math.max(player.getY(),destination.getY()+3)||cruise>mc.level.getMaxBuildHeight()-4))throw new IllegalArgumentException("Cruise altitude does not clear the endpoints or world ceiling");
                if(direct)phase=3;
                initialized=true;
            }
            elapsed++;if(elapsed>2400)throw new IllegalStateException("Flight segment exceeded two minutes");
            mc.options.keyUp.setDown(false);mc.options.keyDown.setDown(false);mc.options.keyLeft.setDown(false);mc.options.keyRight.setDown(false);mc.options.keySprint.setDown(false);
            if(!player.getAbilities().flying){
                if((phase==2||phase==3||phase==4)&&player.onGround()&&Math.abs(player.getY()-destination.getY())<.15
                    &&player.position().distanceTo(Vec3.atBottomCenterOf(destination))<.25){
                    landing();return settled(controller,player.getDeltaMovement().horizontalDistance()<.045);
                }
                // A detour can finish underneath a two-block-high machine passage.
                // After touching its floor, use ordinary walking for the level leg
                // instead of double-tapping Space against the low ceiling forever.
                if(phase==4&&player.onGround()&&Math.abs(player.getY()-detour.get(detourIndex).getY())<.15){
                    BlockPos next=detour.get(detourIndex);
                    if(!segmentClear(player.position(),Vec3.atBottomCenterOf(next)))throw new IllegalStateException("Ground flight-detour passage changed");
                    if(detourWalk==null)detourWalk=PilotJobs.create(Jsons.obj("type","walk","x",next.getX(),"y",next.getY(),"z",next.getZ()));
                    try{if(detourWalk.tick(controller))advanceDetour();}
                    catch(RuntimeException e){throw e;}
                    catch(Exception e){throw new IllegalStateException("Ground flight-detour walking failed",e);}
                    return false;
                }
                if((phase!=0&&phase!=3&&phase!=4)||activation>=20)throw new IllegalStateException("Existing flight is not active; controls released");
                // The user's normal double-space gesture. The game, including installed flight mods, decides whether it is allowed.
                mc.options.keyShift.setDown(false);mc.options.keyJump.setDown(activation==0||activation==3);activation++;
                return false;
            }
            activation=0;
            var velocity=player.getDeltaMovement();double dx=destination.getX()+.5-player.getX(),dz=destination.getZ()+.5-player.getZ();
            if(phase==4)return followDetour(controller);
            double distance=Math.hypot(dx,dz);
            if(stableTicks>0&&(distance>.6||Math.abs(player.getY()-destination.getY())>.65))
                throw new IllegalStateException("Flight arrival changed during server-settle verification; inspect before follow-up actions");
            double targetY=phase==2||phase==3?destination.getY():cruise;
            double horizontalIntent=phase==0?0:Math.min(4,distance);
            Vec3 towards=distance>.001?new Vec3(dx/distance,0,dz/distance):Vec3.ZERO;
            Vec3 proposed=new Vec3(MotionPolicy.preview(dx,velocity.x,towards.x*horizontalIntent),
                MotionPolicy.preview(targetY-player.getY(),velocity.y,Math.max(-2,Math.min(2,targetY-player.getY()))),
                MotionPolicy.preview(dz,velocity.z,towards.z*horizontalIntent));
            double remaining=phase==0?Math.abs(cruise-player.getY()):phase==1?distance:Math.hypot(distance,destination.getY()-player.getY());
            if(!clear(proposed)||stalled(remaining)){planDetour(controller);return false;}
            if(phase==0){
                controls.vertical(cruise);FlightControls.horizontal(steering.horizontal(0,0,velocity.x,velocity.z,player.getYRot(),false,player.getAbilities().getFlyingSpeed()));
                if(player.getY()>=cruise-.8&&player.getY()<=cruise+.8){phase=1;resetProgress();}
            }
            else if(phase==1){
                controls.vertical(cruise);FlightControls.horizontal(steering.horizontal(dx,dz,velocity.x,velocity.z,player.getYRot(),true,player.getAbilities().getFlyingSpeed()));
                if(distance<.4&&velocity.horizontalDistance()<.1){landing();phase=2;resetProgress();}
            }else{
                landing();if(phase==2&&distance>.8)throw new IllegalStateException("Flight drifted outside the verified landing column");
                FlightControls.horizontal(steering.horizontal(dx,dz,velocity.x,velocity.z,player.getYRot(),false,player.getAbilities().getFlyingSpeed()));
                if(hover){
                    controls.vertical(destination.getY());
                    if(settled(controller,distance<.18&&Math.abs(player.getY()-destination.getY())<.25&&velocity.horizontalDistance()<.045&&Math.abs(velocity.y)<.035))return true;
                    controller.note(description());return false;
                }
                // Descend only after horizontal alignment on a direct route; otherwise
                // dropping while traveling would intersect machinery below the corridor.
                if(phase==2||distance<.4) {mc.options.keyJump.setDown(false);mc.options.keyShift.setDown(true);}
                else controls.vertical(Math.max(destination.getY(),started.y));
                if(settled(controller,player.onGround()&&Math.abs(player.getY()-destination.getY())<.15&&distance<.25&&velocity.horizontalDistance()<.045))return true;
            }
            controller.note(description());return false;
        }
        public void cancel(){if(detourWalk!=null)detourWalk.cancel();Actions.releaseControls();}
    }
    public static final class Inspect implements PilotController.Job {
        public String description(){return "Read actual player flight abilities";}
        public boolean tick(PilotController controller)throws Exception{
            var player=Actions.mc().player;var abilities=player.getAbilities();
            var report=Jsons.obj("observedAt",Instant.now().toString(),"mayfly",abilities.mayfly,"flying",abilities.flying,
                "windowActive",Actions.mc().isWindowActive(),"pauseOnLostFocus",Actions.mc().options.pauseOnLostFocus,
                "instabuild",abilities.instabuild,"flyingSpeed",abilities.getFlyingSpeed(),"onGround",player.onGround(),
                "x",player.getX(),"y",player.getY(),"z",player.getZ(),"velocityX",player.getDeltaMovement().x,
                "velocityY",player.getDeltaMovement().y,"velocityZ",player.getDeltaMovement().z);
            Class<?> curios=Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Optional<?> handler=(Optional<?>)curios.getMethod("getCuriosInventory",LivingEntity.class).invoke(null,player);
            JsonArray equipped=new JsonArray();
            if(handler.isPresent()){
                Class<?> api=Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
                IItemHandler stacks=(IItemHandler)api.getMethod("getEquippedCurios").invoke(handler.get());
                for(int i=0;i<stacks.getSlots();i++)if(!stacks.getStackInSlot(i).isEmpty()){
                    var stack=WorldView.stack(stacks.getStackInSlot(i));stack.addProperty("curioSlot",i);equipped.add(stack);
                }
            }
            report.add("equippedCurios",equipped);JsonArray keys=new JsonArray();
            for(var binding:Actions.mc().options.keyMappings){String name=binding.getName().toLowerCase(Locale.ROOT);
                if(name.contains("flight")||name.contains("jetpack")||name.contains("hover"))keys.add(Jsons.obj("name",binding.getName(),"key",binding.getKey().getName()));
            }
            report.add("flightBindings",keys);
            Files.writeString(controller.directory.resolve("last-flight-status.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);
            controller.note("Existing flight: mayfly="+abilities.mayfly+", flying="+abilities.flying+", speed="+abilities.getFlyingSpeed());return true;
        }
    }
}
