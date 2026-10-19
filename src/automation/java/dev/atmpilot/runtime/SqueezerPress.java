package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/** Presses exactly one already displayed blaze powder using ordinary feedback-controlled jumps. */
public final class SqueezerPress implements PilotController.Job {
    private final BlockPos position;private int phase,elapsed,started,hold,jumps,previousHeight,wait;private long before;private boolean initialized,sawAir;
    private final PlanModel.Material material=PlanModel.Material.item("minecraft:blaze_powder");
    public SqueezerPress(JsonObject data){position=Actions.pos(data);}
    public String description(){return "Jump on the own squeezer until its staged input is actually processed";}
    private ItemStack visible()throws Exception {
        var entity=Actions.mc().level.getBlockEntity(position);if(entity==null||!entity.getClass().getName().equals("org.cyclops.integrateddynamics.blockentity.BlockEntitySqueezer"))throw new IllegalStateException("Own squeezer changed");
        Object inventory=entity.getClass().getMethod("getInventory").invoke(entity);return (ItemStack)inventory.getClass().getMethod("getItem",int.class).invoke(inventory,0);
    }
    private int height(){return Integer.parseInt(Actions.mc().level.getBlockState(position).getValues().entrySet().stream().filter(e->e.getKey().getName().equals("height")).findFirst().orElseThrow().getValue().toString());}
    private void report(String state)throws Exception {Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-squeezer-press.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"state",state,"x",position.getX(),"y",position.getY(),"z",position.getZ(),"height",height(),"displayed",WorldView.stack(visible()),"playerInputBefore",before,"playerInputNow",WorldView.count(material),"jumps",jumps,"ticks",elapsed)),StandardCharsets.UTF_8);}
    public boolean tick(PilotController c)throws Exception {try{return step();}catch(Exception e){try{report("failed_staged_input_not_repeated");}catch(Exception ignored){}throw e;}}
    private boolean step()throws Exception {
        var mc=Actions.mc();ServerRules.checkBuild(position);Actions.checkReach(position);
        if(mc.screen!=null||!mc.player.containerMenu.getCarried().isEmpty()||mc.player.getAbilities().flying)throw new IllegalStateException("Close the menu, clear the cursor and disable flight before pressing");
        if(Math.hypot(mc.player.getX()-position.getX()-.5,mc.player.getZ()-position.getZ()-.5)>.2||mc.player.getY()<position.getY()+.2||mc.player.getY()>position.getY()+4)throw new IllegalStateException("Player left the squeezer center");
        ItemStack item=visible();int height=height();
        if(!initialized){initialized=true;before=WorldView.count(material);var recipe=JeiBridge.entry("integrateddynamicscompat:squeezer|8ec537c4").recipe();
            if(recipe.inputs().size()!=1||recipe.inputs().getFirst().alternatives().size()!=1||!recipe.inputs().getFirst().alternatives().getFirst().material().equals(material)||recipe.outputs().size()!=1||!recipe.outputs().getFirst().material().id().equals("fluidstack:minecraft:lava")||recipe.outputs().getFirst().count()!=250)throw new IllegalStateException("Live blaze-powder squeezing recipe changed");
            if(!WorldView.material(item).equals(material)||item.getCount()!=1)throw new IllegalStateException("Stage exactly one visible blaze powder before pressing");report("pressing_staged_item");}
        if(++elapsed>1200||jumps>20)throw new IllegalStateException("Staged squeezing timed out; do not refill");
        if(WorldView.count(material)!=before)throw new IllegalStateException("Player input inventory changed while pressing an existing item");
        if(item.isEmpty()){
            mc.options.keyJump.setDown(false);if(height!=7)throw new IllegalStateException("Staged input disappeared before the plate reached its bottom");
            if(mc.player.onGround()&&++wait>=5){report("staged_item_processed_verify_receiving_fluid");return true;}return false;
        }
        if(!WorldView.material(item).equals(material)||item.getCount()!=1)throw new IllegalStateException("Displayed input changed while pressing");
        if(height==7){mc.options.keyJump.setDown(false);return false;}
        if(phase==0){mc.options.keyJump.setDown(false);if(wait-->0||!mc.player.onGround())return false;previousHeight=height;started=elapsed;sawAir=false;hold=2;jumps++;phase=1;}
        if(phase==1){mc.options.keyJump.setDown(true);if(--hold<=0)phase=2;return false;}
        mc.options.keyJump.setDown(false);if(!mc.player.onGround())sawAir=true;
        if((sawAir&&mc.player.onGround())||height>previousHeight||elapsed-started>60){phase=0;wait=4;}return false;
    }
    public void cancel(){Actions.releaseControls();try{report("interrupted_staged_input_not_repeated");}catch(Exception ignored){}}
}
