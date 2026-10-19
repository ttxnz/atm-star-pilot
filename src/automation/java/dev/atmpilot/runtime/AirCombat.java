package dev.atmpilot.runtime;

import com.google.gson.JsonObject;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Bounded normal flight follow and melee against one explicitly observed Ghast. */
public final class AirCombat implements PilotController.Job,ReflexResumable {
    private final int id;private final UUID uuid;private Flight.Fly flight;private BlockPos aim;private MeleeFighter fighter;private Vec3 origin;private int ticks;private long updated;
    public AirCombat(JsonObject d){id=Jsons.integer(d,"entityId",-1);uuid=UUID.fromString(Jsons.str(d,"uuid",""));if(id<0||!Jsons.str(d,"expectedType","").equals("minecraft:ghast"))throw new IllegalArgumentException("Exact observed Ghast identity is required");}
    public String description(){return "Follow and melee the observed Ghast with ordinary flight";}
    public boolean tick(PilotController c)throws Exception{
        var mc=Actions.mc();if(origin==null)origin=mc.player.position();if(++ticks>1800||origin.distanceTo(mc.player.position())>64)throw new IllegalStateException("Air fight left its bounded area; inspect target");
        var raw=mc.level.getEntity(id);if(raw==null||!raw.isAlive())return true;
        if(!(raw instanceof LivingEntity e)||!e.getUUID().equals(uuid)||!BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals("minecraft:ghast")||!MeleeFighter.permitted(e))throw new IllegalStateException("Observed hostile identity or permission changed");
        if(mc.screen!=null||!mc.player.containerMenu.getCarried().isEmpty()||!mc.player.getAbilities().flying)throw new IllegalStateException("Keep the existing flight active and menus closed");
        double distance=mc.player.distanceTo(e);
        if(distance<=Math.max(1,mc.player.entityInteractionRange()-.45)&&mc.player.hasLineOfSight(e)){
            if(flight!=null){flight.cancel();flight=null;}if(fighter==null)fighter=new MeleeFighter(id,uuid,"minecraft:ghast");return fighter.tick(c);
        }
        if(fighter!=null){fighter.cancel();fighter=null;}
        Vec3 desired=e.position().add(0,1,0),delta=desired.subtract(mc.player.position());
        BlockPos next=BlockPos.containing(mc.player.position().add(delta.scale(Math.min(1,7/Math.max(.01,delta.length())))));
        if(flight==null||c.ticks()-updated>=8&&aim.distSqr(next)>4){
            if(flight!=null)flight.cancel();aim=next;updated=c.ticks();flight=new Flight.Fly(Jsons.obj("x",next.getX(),"y",next.getY(),"z",next.getZ(),"hover",true,"cruiseY",Math.max((int)Math.ceil(mc.player.getY())+4,next.getY()+6)));
        }
        if(flight.tick(c))flight=null;return false;
    }
    public void cancel(){if(flight!=null)flight.cancel();if(fighter!=null)fighter.cancel();Actions.releaseControls();}
    public boolean resumeAfterLocalCombat(){cancel();flight=null;fighter=null;return true;}
}
