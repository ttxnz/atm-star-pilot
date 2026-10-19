package dev.atmpilot.runtime;

import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Tracks an observed own bee with ordinary flight keys; the target is refreshed each client tick. */
public final class FollowBee implements PilotController.Job {
    private final int id;private final UUID uuid;private int elapsed,lastJump=-100;private boolean jumpHeld;
    public FollowBee(Entity entity){id=entity.getId();uuid=entity.getUUID();}
    public String description(){return "Approach a registered own bee using normal flight";}
    public boolean tick(PilotController c){
        var mc=Actions.mc();var p=mc.player;Entity bee=mc.level.getEntity(id);if(bee==null||!bee.isAlive()||!uuid.equals(bee.getUUID())||!OwnBeeRecords.tracked(bee))throw new IllegalStateException("Registered bee is no longer available for this approach");
        if(++elapsed>600)throw new IllegalStateException("Own bee approach exceeded thirty seconds");if(mc.screen!=null||!p.getAbilities().flying)throw new IllegalStateException("Visible bee approach requires the existing permitted flight");
        var velocity=p.getDeltaMovement();Vec3 goal=bee.position().add(bee.getDeltaMovement().scale(4)).add(0,1,0);double dx=goal.x-p.getX(),dz=goal.z-p.getZ(),dy=goal.y-p.getY();double horizontal=Math.hypot(dx,dz);
        Vec3 proposed=velocity.scale(5).add(horizontal<.01?Vec3.ZERO:new Vec3(dx,0,dz).normalize().scale(Math.min(2,horizontal))).add(0,Math.max(-2,Math.min(2,dy)),0);
        var swept=p.getBoundingBox().expandTowards(proposed).inflate(.03);
        for(BlockPos block:BlockPos.betweenClosed(BlockPos.containing(swept.minX,swept.minY,swept.minZ),BlockPos.containing(swept.maxX,swept.maxY,swept.maxZ)))if(!LoadedWorld.has(block)||!mc.level.getFluidState(block).isEmpty())throw new IllegalStateException("Bee approach reaches an unloaded or fluid corridor");
        if(!mc.level.noCollision(p,swept))throw new IllegalStateException("Bee approach corridor is obstructed");
        mc.options.keyUp.setDown(false);mc.options.keyDown.setDown(false);mc.options.keyLeft.setDown(false);mc.options.keyRight.setDown(false);mc.options.keySprint.setDown(false);
        if(p.distanceTo(bee)<=1.35&&mc.player.hasLineOfSight(bee)&&velocity.horizontalDistance()<.25&&Math.abs(velocity.y)<.08){Actions.releaseControls();return true;}
        double sx=dx-velocity.x*4,sz=dz-velocity.z*4;if(Math.hypot(sx,sz)>.05){Actions.look((float)Math.toDegrees(Math.atan2(-sx,sz)),8);mc.options.keyUp.setDown(true);}
        double vertical=dy-velocity.y*4;boolean up=vertical>.25&&(jumpHeld||elapsed-lastJump>=10);if(up&&!jumpHeld)lastJump=elapsed;jumpHeld=up;mc.options.keyJump.setDown(up);mc.options.keyShift.setDown(vertical<-.18);return false;
    }
    public void cancel(){Actions.releaseControls();}
}
