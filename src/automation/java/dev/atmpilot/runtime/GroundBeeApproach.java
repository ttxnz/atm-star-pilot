package dev.atmpilot.runtime;

import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;

/** Land normally beside a tracked bee, then let BeeCapture re-check its live reach. */
public final class GroundBeeApproach implements PilotController.Job {
    private final int id;private final UUID uuid;private PilotController.Job flight;
    public GroundBeeApproach(Entity bee){id=bee.getId();uuid=bee.getUUID();}
    public String description(){return "Land beside a registered own bee using ordinary flight";}
    private Entity bee(){var e=Actions.mc().level.getEntity(id);
        if(e==null||!e.isAlive()||!uuid.equals(e.getUUID())||!OwnBeeRecords.tracked(e))throw new IllegalStateException("Registered bee identity changed during landing approach");return e;}
    private BlockPos landing(Entity bee){
        var mc=Actions.mc();var origin=bee.blockPosition();List<BlockPos> candidates=new ArrayList<>();
        int ceiling=Math.min(mc.level.getMaxBuildHeight()-2,Math.max((int)Math.ceil(mc.player.getY())+6,origin.getY()+8));
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=-3;dy<=2;dy++){
            BlockPos p=origin.offset(dx,dy,dz);Vec3 feet=Vec3.atBottomCenterOf(p);
            if(feet.distanceTo(bee.position())>2.3||feet.subtract(bee.position()).horizontalDistance()<.7)continue;
            if(!LoadedWorld.has(p.below())||!mc.level.getBlockState(p.below()).isFaceSturdy(mc.level,p.below(),Direction.UP))continue;
            boolean clear=true;
            for(int y=p.getY();y<=ceiling;y++){
                BlockPos cell=new BlockPos(p.getX(),y,p.getZ());
                if(!LoadedWorld.has(cell)||!mc.level.getBlockState(cell).getCollisionShape(mc.level,cell).isEmpty()||!mc.level.getFluidState(cell).isEmpty()){clear=false;break;}
            }
            if(!clear)continue;
            Vec3 eye=feet.add(0,mc.player.getEyeHeight(),0);
            var ray=mc.level.clip(new ClipContext(eye,bee.getEyePosition(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
            if(ray.getType()==HitResult.Type.MISS)candidates.add(p);
        }
        return candidates.stream().min(Comparator.comparingDouble(p->Vec3.atBottomCenterOf(p).distanceToSqr(bee.position())+.01*p.distSqr(mc.player.blockPosition())))
            .orElseThrow(()->new IllegalStateException("No clear dry landing column beside the observed bee"));
    }
    public boolean tick(PilotController c)throws Exception{
        Entity target=bee();
        if(flight==null){BlockPos p=landing(target);var mc=Actions.mc();
            flight=new Flight.Fly(Jsons.obj("x",p.getX(),"y",p.getY(),"z",p.getZ(),"cruiseY",Math.max((int)Math.ceil(mc.player.getY())+6,p.getY()+8)));
        }
        return flight.tick(c);
    }
    public void cancel(){if(flight!=null)flight.cancel();Actions.releaseControls();}
}
