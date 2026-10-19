package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** One normal empty-hand crouch/right-click on a specifically confirmed existing transport pad. */
public final class PadTravel implements PilotController.Job,ReflexResumable {
    private final BlockPos source;private JsonObject site,report;private int elapsed,stable,crouchTicks;private boolean sent;
    public PadTravel(JsonObject data){source=Actions.pos(data);}
    public String description(){return "Authorized survival travel: existing teleport pad";}
    public boolean resumeAfterLocalCombat(){if(sent)return false;crouchTicks=0;Actions.releaseControls();return true;}
    private void save(String state)throws Exception{report.addProperty("state",state);report.addProperty("updatedAt",Instant.now().toString());Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-pad-travel.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);}
    public boolean tick(PilotController c)throws Exception {
        var mc=Actions.mc();if(elapsed++==0){
            site=TransportRules.site(source);if(site==null)throw new IllegalStateException("This pad has no user-confirmed existing exit");
            Actions.closeMenu();Actions.checkReach(source);
            if(mc.player.getAbilities().flying||!mc.player.onGround()||!mc.player.getOffhandItem().isEmpty())throw new IllegalStateException("Land normally and empty the offhand before using the pad");
            int empty=-1;for(int i=0;i<9;i++)if(mc.player.getInventory().getItem(i).isEmpty()){empty=i;break;}
            int displacedTo=-1;
            if(empty<0){
                for(int i=9;i<36;i++)if(mc.player.getInventory().getItem(i).isEmpty()){displacedTo=i;break;}
                if(displacedTo<0)throw new IllegalStateException("No empty player-inventory slot to prepare empty hands for pad travel");
                empty=mc.player.getInventory().selected;
                Actions.click(mc.player.inventoryMenu.containerId,displacedTo,empty,net.minecraft.world.inventory.ClickType.SWAP);
            }
            Actions.select(empty);report=Jsons.obj("startedAt",Instant.now().toString(),"source",site,"sourceState",mc.level.getBlockState(source).toString(),"clickSentOnce",false,"requiredStableCrouchTicks",30,"displacedToPlayerSlot",displacedTo);
            save("preparing_normal_crouch");
        }
        // Keep the real key held through arrival. Check pose, not just the requested key state.
        mc.options.keyShift.setDown(true);
        if(!sent){
            crouchTicks=mc.player.isCrouching()&&mc.player.onGround()?crouchTicks+1:0;
            if(crouchTicks<30){if(elapsed>200)throw new IllegalStateException("Could not establish stable normal crouching before pad use");return false;}
            if(!mc.player.getMainHandItem().isEmpty()||!mc.player.getOffhandItem().isEmpty())throw new IllegalStateException("Empty-hand preparation was not confirmed before pad interaction");
            mc.options.keyShift.setDown(true);ServerRules.checkOpen(source);Actions.checkReach(source);var shape=mc.level.getBlockState(source).getShape(mc.level,source);if(shape.isEmpty())throw new IllegalStateException("Observed pad has no normal clickable shape");var bounds=shape.bounds();Vec3 point=new Vec3(source.getX()+(bounds.minX+bounds.maxX)/2,source.getY()+bounds.maxY-.001,source.getZ()+(bounds.minZ+bounds.maxZ)/2);Actions.lookAt(point);
            BlockHitResult hit=mc.level.clip(new ClipContext(mc.player.getEyePosition(),point,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));if(hit.getType()!=HitResult.Type.BLOCK||!hit.getBlockPos().equals(source))throw new IllegalStateException("Confirmed pad is not directly visible");
            report.addProperty("stableCrouchTicksAtClick",crouchTicks);report.addProperty("crouchingAtClick",mc.player.isCrouching());report.addProperty("shiftKeyDownAtClick",mc.player.isShiftKeyDown());report.addProperty("clickSentOnce",true);save("interaction_intent");var result=mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);report.addProperty("localInteractionResult",result.toString());mc.player.swing(InteractionHand.MAIN_HAND);sent=true;save("sent_unverified");return false;
        }
        boolean arrived=mc.level.dimension().location().toString().equals(Jsons.str(site,"targetDimension",""))&&LoadedWorld.has(mc.player.blockPosition())&&LoadedWorld.has(mc.player.blockPosition().above())&&!(mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen);
        stable=arrived?stable+1:0;if(stable>=10){Actions.releaseControls();report.add("arrival",Jsons.obj("dimension",mc.level.dimension().location().toString(),"x",mc.player.getX(),"y",mc.player.getY(),"z",mc.player.getZ()));save("arrived_verified");return true;}
        if(elapsed>1200){save("arrival_not_verified");throw new IllegalStateException("Existing pad arrival was not verified; interaction was not repeated");}return false;
    }
    public void cancel(){Actions.releaseControls();if(report!=null)try{save("interrupted_needs_replan");}catch(Exception ignored){}}
}
