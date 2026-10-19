package dev.atmpilot.runtime;

import com.google.gson.JsonObject;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.*;

/** Normal alternate item use on one observed own water source; never changes world state directly. */
public final class EntropyFreeze implements PilotController.Job {
    private final BlockPos pos;private int ticks;private boolean sent;
    public EntropyFreeze(JsonObject data){pos=Actions.pos(data);}
    public String description(){return "Freeze one own water source with the held charged entropy manipulator";}
    public boolean tick(PilotController c){
        var mc=Actions.mc();ServerRules.checkBuild(pos);Actions.checkReach(pos);
        if(mc.screen!=null||!mc.player.containerMenu.getCarried().isEmpty()||!WorldView.material(mc.player.getMainHandItem()).id().equals("ae2:entropy_manipulator"))throw new IllegalStateException("Entropy tool, menu or cursor changed");
        if(sent&&mc.level.getBlockState(pos).is(Blocks.ICE)){Actions.releaseControls();return true;}
        if(!mc.level.getFluidState(pos).is(Fluids.WATER)||!mc.level.getFluidState(pos).isSource())throw new IllegalStateException("Observe an own water source before freezing");
        SneakControl.hold();Actions.lookAt(Vec3.atCenterOf(pos).add(0,.45,0));
        if(++ticks<30)return false;
        if(!sent){
            if(!mc.player.isShiftKeyDown())throw new IllegalStateException("Native alternate-use key did not activate");
            Vec3 eye=mc.player.getEyePosition();var hit=mc.level.clip(new ClipContext(eye,eye.add(mc.player.getViewVector(1).scale(mc.player.blockInteractionRange())),ClipContext.Block.OUTLINE,ClipContext.Fluid.ANY,mc.player));
            if(hit.getType()!=HitResult.Type.BLOCK||!hit.getBlockPos().equals(pos))throw new IllegalStateException("Entropy fluid ray is obstructed");
            var recipe=JeiBridge.entry("ae2:entropy|ae2:entropy/cool/water_ice").recipe();
            if(!recipe.type().equals("ae2:entropy")||recipe.outputs().size()!=1||!recipe.outputs().getFirst().material().id().equals("minecraft:ice"))throw new IllegalStateException("Native water cooling recipe changed");
            ServerRules.checkItemUse();mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);mc.player.swing(InteractionHand.MAIN_HAND);sent=true;
        }
        if(ticks>110)throw new IllegalStateException("Native freezing was not confirmed; no item use repeated");return false;
    }
    public void cancel(){Actions.releaseControls();}
}
