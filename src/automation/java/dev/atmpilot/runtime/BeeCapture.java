package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Exactly one ordinary right-click with an empty cage; no direct capture/spawn helper is invoked. */
public final class BeeCapture implements PilotController.Job {
    private final int entityId;private final String expectedType,species,item;private UUID uuid;private int phase,wait,elapsed,emptyBefore,filledBefore,matchingBefore;private boolean sent;private Class<?> cageApi;
    public BeeCapture(JsonObject data){entityId=Jsons.integer(data,"entityId",-1);expectedType=Jsons.str(data,"expectedType","");species=Jsons.str(data,"expectedSpecies",expectedType);item=Jsons.str(data,"item","productivebees:sturdy_bee_cage");
        if(entityId<0||!(expectedType.startsWith("productivebees:")||expectedType.equals("minecraft:bee"))||!Set.of("productivebees:sturdy_bee_cage","productivebees:bee_cage").contains(item)||expectedType.equals("productivebees:configurable_bee")&&!data.has("expectedSpecies"))throw new IllegalArgumentException("An observed bee entity, exact type/species and supported cage are required");}
    public String description(){return "Capture one observed own-area bee with an ordinary cage interaction";}
    public boolean interactionSent(){return sent;}
    private boolean filled(ItemStack stack)throws Exception{return (Boolean)cageApi.getMethod("isFilled",ItemStack.class).invoke(null,stack);}
    private String species(ItemStack stack)throws Exception{return (String)cageApi.getMethod("getBeeType",ItemStack.class).invoke(null,stack);}
    private int count(int kind)throws Exception{int count=0;var inv=Actions.mc().player.getInventory();for(int i=0;i<36;i++){var stack=inv.getItem(i);if(!BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item))continue;boolean full=filled(stack);if(kind==0&&!full||kind==1&&full||kind==2&&full&&species.equals(species(stack)))count+=stack.getCount();}return count;}
    private Entity target(){var mc=Actions.mc();Entity entity=mc.level.getEntity(entityId);if(!(entity instanceof Bee)||!entity.isAlive()||!BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(expectedType)||uuid!=null&&!uuid.equals(entity.getUUID()))throw new IllegalStateException("Observed bee identity changed");try{ServerRules.checkBuild(entity.blockPosition());}catch(IllegalStateException outside){if(!OwnBeeRecords.tracked(entity))throw outside;}return entity;}
    private void report(String state)throws Exception {Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-bee-capture.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"state",state,"entityId",entityId,"entityUuid",uuid==null?null:uuid.toString(),"expectedType",expectedType,"expectedSpecies",species,"cageItem",item,"clickSentOnce",sent,"emptyBefore",emptyBefore,"filledBefore",filledBefore,"matchingBefore",matchingBefore,"phase",phase)),StandardCharsets.UTF_8);}
    public boolean tick(PilotController c)throws Exception{try{return step(c);}catch(Exception|LinkageError e){try{report("stopped_needs_replan");}catch(Exception ignored){}throw e;}}
    private boolean step(PilotController c)throws Exception {
        var mc=Actions.mc();if(++elapsed>240)throw new IllegalStateException("Cage interaction was not verified; do not repeat blindly");if(wait-->0)return false;
        if(phase==0){
            if(mc.screen!=null||!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Close the screen and empty the cursor before capturing");cageApi=Class.forName("cy.jdkdigital.productivebees.common.item.BeeCage",true,PilotJobs.class.getClassLoader());var entity=target();uuid=entity.getUUID();
            emptyBefore=count(0);filledBefore=count(1);matchingBefore=count(2);int found=-1;for(int i=0;i<36;i++){var stack=mc.player.getInventory().getItem(i);if(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)&&!filled(stack)){found=i;break;}}if(found<0)throw new IllegalStateException("No empty cage is available");
            if(found>=9){Actions.click(mc.player.inventoryMenu.containerId,found,8,ClickType.SWAP);Actions.select(8);}else Actions.select(found);phase=1;wait=6;report("selecting_empty_cage");return false;
        }
        if(phase==1){var entity=target();if(mc.player.distanceTo(entity)>mc.player.entityInteractionRange()||!mc.player.hasLineOfSight(entity))throw new IllegalStateException("Bee must be visible within normal interaction reach");Actions.lookAt(entity.getEyePosition());phase=2;wait=4;return false;}
        if(phase==2){
            var entity=target();if(mc.player.distanceTo(entity)>mc.player.entityInteractionRange()||!mc.player.hasLineOfSight(entity)||!BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString().equals(item)||filled(mc.player.getMainHandItem()))throw new IllegalStateException("Bee reach or empty cage changed before the interaction");
            mc.gameMode.interact(mc.player,entity,InteractionHand.MAIN_HAND);mc.player.swing(InteractionHand.MAIN_HAND);sent=true;phase=3;wait=8;report("interaction_sent_once");return false;
        }
        if(phase==3){
            Entity entity=mc.level.getEntity(entityId);if(count(0)==emptyBefore-1&&count(1)==filledBefore+1&&count(2)==matchingBefore+1&&(entity==null||!entity.isAlive())){report("completed_verified");return true;}return false;
        }
        throw new IllegalStateException("Invalid capture phase");
    }
    public void cancel(){try{report("interrupted_needs_replan");}catch(Exception ignored){}Actions.releaseControls();}
}
