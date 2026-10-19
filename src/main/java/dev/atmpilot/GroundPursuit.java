package dev.atmpilot;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Follows the current entity, never an external position snapshot. Only normal movement keys. */
public final class GroundPursuit {
    private List<GridPathfinder.Cell> path = List.of();
    private int index, stuck;
    private long plannedAt = -1;
    private Vec3 plannedTarget, previous;
    private static GridPathfinder.Cell cell(BlockPos p) { return new GridPathfinder.Cell(p.getX(),p.getY(),p.getZ()); }
    public static boolean stand(BlockPos p) {
        var mc = Actions.mc();
        for (var part : List.of(p.below(), p, p.above())) {
            if (!LoadedWorld.has(part)) return false;
            var state = mc.level.getBlockState(part);
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (!state.getFluidState().isEmpty() || id.contains("fire") || id.contains("cactus") || id.contains("magma") || id.contains("powder_snow")) return false;
        }
        return mc.level.getBlockState(p).getCollisionShape(mc.level,p).isEmpty()
            && mc.level.getBlockState(p.above()).getCollisionShape(mc.level,p.above()).isEmpty()
            && mc.level.getBlockState(p.below()).isFaceSturdy(mc.level,p.below(),Direction.UP);
    }
    public static List<BlockPos> stances(LivingEntity entity) {
        var mc=Actions.mc(); List<BlockPos> result=new ArrayList<>();
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=-1;dy<=1;dy++) {
            var p=entity.blockPosition().offset(dx,dy,dz);
            if(stand(p) && Vec3.atBottomCenterOf(p).distanceTo(entity.position())<2.5) result.add(p);
        }
        result.sort(Comparator.comparingDouble(p->Vec3.atBottomCenterOf(p).distanceToSqr(mc.player.position())));
        return result;
    }
    public boolean tick(LivingEntity target, double stopRange, PilotController controller) {
        var mc=Actions.mc(); var player=mc.player;
        if(mc.screen!=null || player.getAbilities().flying) throw new IllegalStateException("Ground pursuit requires a closed screen and flight off");
        double distance=player.distanceTo(target);
        if(distance>24)throw new IllegalStateException("Moving target left the bounded local pursuit radius");
        if(distance<=stopRange && player.hasLineOfSight(target)){Actions.releaseControls();return true;}
        if(previous!=null && player.position().distanceToSqr(previous)<.0004)stuck++;else stuck=0;
        previous=player.position();
        if(stuck>60)throw new IllegalStateException("Moving-target approach is blocked; inspect before retrying");
        boolean exhausted=index>=path.size();
        if(!exhausted){var next=path.get(index);if(player.position().distanceToSqr(new Vec3(next.x()+.5,next.y(),next.z()+.5))>16)plannedAt=-1;}
        double displacement=plannedTarget==null?Double.POSITIVE_INFINITY:plannedTarget.distanceToSqr(target.position());
        if(MotionPolicy.replan(controller.ticks(),plannedAt,displacement,exhausted||stuck>8)) {
            boolean found=false;
            for(var goal:stances(target).stream().limit(6).toList()) {
                try{path=GridPathfinder.find(cell(player.blockPosition()),cell(goal),p->stand(new BlockPos(p.x(),p.y(),p.z())),3000);index=0;found=true;break;}
                catch(IllegalArgumentException|IllegalStateException ignored){}
            }
            if(!found)throw new IllegalStateException("No dry loaded path to the current moving target");
            plannedTarget=target.position();plannedAt=controller.ticks();
        }
        if(index>=path.size()){Actions.releaseControls();return false;}
        var next=path.get(index);var point=new Vec3(next.x()+.5,next.y(),next.z()+.5);
        var delta=point.subtract(player.position());
        if(delta.horizontalDistanceSqr()<.10 && Math.abs(delta.y)<.8){index++;return false;}
        if(!stand(new BlockPos(next.x(),next.y(),next.z()))){plannedAt=-1;Actions.releaseControls();return false;}
        float desired=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
        float turn=Mth.wrapDegrees(desired-player.getYRot());
        Actions.look(player.getYRot()+Mth.clamp(turn,-40,40),8);
        mc.options.keyUp.setDown(Math.abs(turn)<55);
        mc.options.keySprint.setDown(distance>6 && player.getFoodData().getFoodLevel()>8 && Math.abs(turn)<20);
        mc.options.keyJump.setDown(delta.y>.3 && player.onGround());
        mc.options.keyShift.setDown(false);
        return false;
    }
    public void cancel(){Actions.releaseControls();}
}
