package dev.atmpilot.runtime;

import com.google.gson.JsonObject;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.*;

/** Normal mining that preserves an explicitly observed tool and its components. */
public final class HeldToolMine implements PilotController.Job,ReflexResumable {
    private final BlockPos pos;
    private final PlanModel.Material tool;
    private final String expected;
    private final boolean allowBlockEntity;
    private int elapsed;
    public HeldToolMine(JsonObject d){pos=Actions.pos(d);tool=new PlanModel.Material(Jsons.str(d,"tool",""),Jsons.str(d,"components",""));expected=Jsons.str(d,"block","");allowBlockEntity=Jsons.bool(d,"allowBlockEntity",false);if(tool.id().isBlank()||expected.isBlank())throw new IllegalArgumentException("Observed tool components and target block are required");}
    public String description(){return "Mine "+expected+" with the observed held tool at "+pos.toShortString();}
    public boolean tick(PilotController c){
        var mc=Actions.mc();ServerRules.checkBuild(pos);Actions.checkReach(pos);
        if(mc.screen!=null||!mc.player.containerMenu.getCarried().isEmpty()||!WorldView.material(mc.player.getMainHandItem()).equals(tool))throw new IllegalStateException("Held tool/menu changed; no automatic tool substitution");
        if(!LoadedWorld.has(pos))throw new IllegalStateException("Mining target unloaded");
        var state=mc.level.getBlockState(pos);String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if(state.isAir()){mc.gameMode.stopDestroyBlock();return true;}
        if(!id.equals(expected)||!mc.level.getFluidState(pos).isEmpty()||state.getDestroySpeed(mc.level,pos)<0||(state.hasBlockEntity()&&!allowBlockEntity))throw new IllegalStateException("Observed mining block changed or is not authorized");
        if(state.requiresCorrectToolForDrops()&&!mc.player.getMainHandItem().isCorrectToolForDrops(state))throw new IllegalStateException("Held tool cannot collect this block");
        Vec3 point=Vec3.atCenterOf(pos);var hit=mc.level.clip(new net.minecraft.world.level.ClipContext(mc.player.getEyePosition(),point,net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.ANY,mc.player));
        if(hit.getType()!=HitResult.Type.BLOCK||!hit.getBlockPos().equals(pos))throw new IllegalStateException("Mining target is obstructed");
        Actions.lookAt(point);if(elapsed++==0)mc.gameMode.startDestroyBlock(pos,hit.getDirection());
        mc.gameMode.continueDestroyBlock(pos,hit.getDirection());mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if(elapsed>1200)throw new IllegalStateException("Held-tool mining timed out");return false;
    }
    public void cancel(){if(Actions.mc().gameMode!=null)Actions.mc().gameMode.stopDestroyBlock();Actions.releaseControls();}
    public boolean resumeAfterLocalCombat(){elapsed=0;return true;}
}
