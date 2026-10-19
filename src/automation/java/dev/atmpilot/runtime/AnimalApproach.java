package dev.atmpilot.runtime;

import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.*;

/** A single local landing/follow operation that keeps reading the moving animal every tick. */
public final class AnimalApproach {
    private Flight.Fly flight;
    private Flight.Toggle toggle;
    private BlockPos landing;
    private final GroundPursuit ground=new GroundPursuit();
    private boolean landed;
    private long updated=-1;
    private BlockPos choose(LivingEntity target){
        var mc=Actions.mc();
        for(var p:GroundPursuit.stances(target)){
            boolean clear=true;
            for(int y=p.getY();y<=Math.ceil(mc.player.getY())+4;y++){
                var at=new BlockPos(p.getX(),y,p.getZ());
                if(!LoadedWorld.has(at)||!mc.level.getBlockState(at).getCollisionShape(mc.level,at).isEmpty()||!mc.level.getFluidState(at).isEmpty()){clear=false;break;}
            }
            if(clear)return p;
        }
        throw new IllegalStateException("No dry loaded landing column beside the current animal");
    }
    public boolean tick(LivingEntity target,PilotController c){
        var mc=Actions.mc();
        if(mc.player.distanceTo(target)>48)throw new IllegalStateException("Animal left the bounded local landing radius");
        if(!landed && (mc.player.getAbilities().flying||!mc.player.onGround())){
            if(flight==null){landing=choose(target);flight=new Flight.Fly(Jsons.obj("x",landing.getX(),"y",landing.getY(),"z",landing.getZ(),"cruiseY",Math.max((int)Math.ceil(mc.player.getY())+4,landing.getY()+8)));updated=c.ticks();}
            if(c.ticks()-updated>=4 && target.blockPosition().distSqr(landing)>25){landing=choose(target);flight.retarget(landing);updated=c.ticks();}
            if(!flight.tick(c))return false;
            landed=true;Actions.releaseControls();return false;
        }
        landed=true;
        if(mc.player.getAbilities().flying){if(toggle==null)toggle=new Flight.Toggle(Jsons.obj("flying",false));toggle.tick(c);return false;}
        if(!mc.player.onGround())return false;
        return ground.tick(target,Math.max(1,mc.player.entityInteractionRange()-.45),c);
    }
    public void cancel(){ground.cancel();if(flight!=null)Actions.releaseControls();if(toggle!=null)toggle.cancel();}
    public void afterCombat(){if(flight!=null)flight.resumeAfterLocalCombat();ground.cancel();}
}
