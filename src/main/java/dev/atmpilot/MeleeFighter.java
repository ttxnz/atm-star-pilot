package dev.atmpilot;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.phys.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;

/** One bounded fight. Every swing revalidates identity, reach, visibility, cooldown and bystanders. */
public final class MeleeFighter implements PilotController.Job {
    private final int entityId;
    private final UUID uuid;
    private final String type;
    private final GroundPursuit pursuit=new GroundPursuit();
    private int elapsed,swings,originalSlot=-1,weaponSlot=-1,unseen,unchanged;
    private long detectedTick=-1,firstAttackTick=-1,lastSwing=-100;
    private float firstHealth=-1,lastHealth=-1;
    private Vec3 origin;
    private String state="pending";
    public MeleeFighter(int entityId,UUID uuid,String type){
        if(entityId<0||uuid==null||net.minecraft.resources.ResourceLocation.tryParse(type)==null||type.equals("minecraft:player"))throw new IllegalArgumentException("Exact identity of an observed non-player mob is required; authorization is checked on every tick");
        this.entityId=entityId;this.uuid=uuid;this.type=type;
    }
    public String description(){return "Local ordinary melee against observed "+type;}
    public static boolean permitted(LivingEntity entity){
        var mc=Actions.mc();
        if(!(entity instanceof Mob mob)||!entity.isAlive()||entity.isAlliedTo(mc.player)||entity.hasCustomName()||entity.isPassenger()||mob.isNoAi()
            ||entity instanceof net.minecraft.world.entity.npc.AbstractVillager||entity instanceof net.minecraft.world.entity.animal.IronGolem||entity instanceof net.minecraft.world.entity.animal.SnowGolem||entity instanceof net.minecraft.world.entity.animal.allay.Allay
            ||entity instanceof net.minecraft.world.entity.TamableAnimal pet&&pet.getOwnerUUID()!=null
            ||entity instanceof net.minecraft.world.entity.animal.horse.AbstractHorse horse&&horse.isTamed())return false;
        var rules=ServerRules.snapshot();var base=rules.getAsJsonObject("sharedBase");
        if(base!=null&&Jsons.str(base,"dimension","").equals(Actions.mc().level.dimension().location().toString())&&base.has("conservativeBounds")){
            var box=base.getAsJsonObject("conservativeBounds");var p=entity.blockPosition();
            if(p.getX()>=Jsons.integer(box,"minX",Integer.MIN_VALUE)&&p.getX()<=Jsons.integer(box,"maxX",Integer.MAX_VALUE)
                &&p.getZ()>=Jsons.integer(box,"minZ",Integer.MIN_VALUE)&&p.getZ()<=Jsons.integer(box,"maxZ",Integer.MAX_VALUE))return false;
        }
        var guard=rules.getAsJsonObject("survivalGuard");if(guard==null)return false;
        if(guard.has("protectedEntityUuids"))for(var value:guard.getAsJsonArray("protectedEntityUuids")){
            if(entity.getUUID().toString().equals(value.getAsString()))return false;
        }
        if(guard.has("protectedEntityAreas"))for(var value:guard.getAsJsonArray("protectedEntityAreas")){
            var box=value.getAsJsonObject();var p=entity.blockPosition();
            if(Jsons.str(box,"dimension","").equals(mc.level.dimension().location().toString())&&p.getX()>=Jsons.integer(box,"minX",Integer.MAX_VALUE)&&p.getX()<=Jsons.integer(box,"maxX",Integer.MIN_VALUE)&&p.getZ()>=Jsons.integer(box,"minZ",Integer.MAX_VALUE)&&p.getZ()<=Jsons.integer(box,"maxZ",Integer.MIN_VALUE)&&entity instanceof net.minecraft.world.entity.animal.Animal)return false;
        }
        String type=BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        if(CombatPolicy.permitted(type,false,false,false))return true;
        var damage=mc.player.getLastDamageSource();boolean attacker=damage!=null&&damage.getEntity()!=null&&damage.getEntity().getUUID().equals(entity.getUUID());
        if(Jsons.bool(guard,"counterAttackOnDamage",false)&&attacker)return true;
        if(RealtimeCombat.active()){var fight=RealtimeCombat.status().getAsJsonObject("fight");if(fight!=null&&entity.getUUID().toString().equals(Jsons.str(fight,"uuid","")))return true;}
        if(!Jsons.bool(guard,"clearBlockingMobs",false)||mc.screen!=null||!PilotMod.CONTROLLER.running())return false;
        try{ServerRules.checkBuild(mc.player.blockPosition());ServerRules.checkBuild(entity.blockPosition());}catch(IllegalStateException protectedArea){return false;}
        boolean inPath=mc.options.keyUp.isDown()&&entity.getBoundingBox().intersects(mc.player.getBoundingBox().expandTowards(mc.player.getLookAngle().multiply(.9,0,.9)).inflate(.1));
        var job=PilotMod.CONTROLLER.status();String action=job.has("active")&&!job.get("active").isJsonNull()?job.get("active").getAsString().toLowerCase(java.util.Locale.ROOT):"";
        boolean building=action.equals("place")||action.contains("platform")||action.contains("blueprint");
        boolean inPlacement=building&&mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit&&hit.getEntity()==entity;
        return inPath||inPlacement;
    }
    public JsonObject status(){return Jsons.obj("state",state,"entityId",entityId,"uuid",uuid.toString(),"type",type,"swings",swings,
        "detectedTick",detectedTick,"firstAttackTick",firstAttackTick,"reactionTicks",firstAttackTick<0?-1:firstAttackTick-detectedTick,
        "firstHealth",firstHealth,"lastHealth",lastHealth,"elapsedTicks",elapsed);}
    private void finish(String value){
        state=value;Actions.releaseControls();var mc=Actions.mc();
        if(originalSlot>=0 && mc.player.getInventory().selected==weaponSlot && !SurvivalReflex.status().get("manualHold").getAsBoolean())Actions.select(originalSlot);
        try{var report=status();report.addProperty("observedAt",Instant.now().toString());Files.writeString(PilotMod.CONTROLLER.directory.resolve("last-combat.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);}
        catch(Exception e){PilotMod.LOG.warn("Cannot save local combat result",e);}
    }
    public boolean tick(PilotController controller){
        var mc=Actions.mc();var player=mc.player;
        if(player.getHealth()<=1)throw new IllegalStateException("Health reached the authorized emergency return threshold");
        if(mc.screen!=null || !player.containerMenu.getCarried().isEmpty() || player.isUsingItem())
            throw new IllegalStateException("Melee needs free ordinary controls and an empty cursor");
        if(++elapsed>200)throw new IllegalStateException("Local fight timed out; use the verified safety return");
        Entity observed=mc.level.getEntity(entityId);
        if(observed==null){
            if(lastHealth<=0 && firstAttackTick>=0){finish("completed_verified_dead");return true;}
            if(++unseen<=10){Actions.releaseControls();return false;}
            throw new IllegalStateException("Combat target disappeared without a verified death");
        }
        if(!(observed instanceof LivingEntity target) || !uuid.equals(observed.getUUID()) || !type.equals(BuiltInRegistries.ENTITY_TYPE.getKey(observed.getType()).toString()))
            throw new IllegalStateException("Observed combat identity changed");
        unseen=0;lastHealth=target.getHealth();
        if(lastHealth<=0 || target.isDeadOrDying()){finish("completed_verified_dead");return true;}
        if(!permitted(target))throw new IllegalStateException("Target no longer meets ordinary hostile-only combat rules");
        if(origin==null){
            origin=player.position();detectedTick=controller.ticks();firstHealth=lastHealth;originalSlot=player.getInventory().selected;
            for(int i=0;i<9;i++)if(player.getInventory().getItem(i).getItem() instanceof SwordItem){weaponSlot=i;break;}
            if(weaponSlot<0){
                for(int i=9;i<36;i++)if(player.getInventory().getItem(i).getItem() instanceof SwordItem){
                    if(mc.screen!=null||!player.containerMenu.getCarried().isEmpty()||player.containerMenu.containerId!=0)throw new IllegalStateException("Close the menu and return cursor before equipping the defensive sword");
                    Actions.click(player.inventoryMenu.containerId,i,8,net.minecraft.world.inventory.ClickType.SWAP);weaponSlot=8;Actions.select(8);state="equipping";return false;
                }
            }
            if(weaponSlot<0)throw new IllegalStateException("No ordinary melee sword in inventory");
            Actions.select(weaponSlot);state="tracking";
        }
        if(player.position().distanceTo(origin)>7 || player.distanceTo(target)>10)throw new IllegalStateException("Hostile left bounded defensive pursuit");
        if(state.equals("equipping")&&!(player.getMainHandItem().getItem() instanceof SwordItem)){if(elapsed>40)throw new IllegalStateException("Defensive sword swap was not acknowledged");return false;}
        if(player.getInventory().selected!=weaponSlot || !(player.getMainHandItem().getItem() instanceof SwordItem||player.getMainHandItem().getItem() instanceof net.minecraft.world.item.AxeItem))
            throw new IllegalStateException("Melee weapon changed");
        boolean bystander=mc.level.getEntities(player,target.getBoundingBox().inflate(2),e->e instanceof LivingEntity && e!=target && e.isAlive() && (!(e instanceof LivingEntity living)||!permitted(living))).size()>0;
        if(bystander&&player.getMainHandItem().getItem() instanceof SwordItem){
            int single=-1;for(int i=0;i<9;i++)if(player.getInventory().getItem(i).getItem() instanceof net.minecraft.world.item.AxeItem){single=i;break;}
            if(single<0){Actions.releaseControls();state="waiting_for_sweep_clearance";return false;}
            weaponSlot=single;Actions.select(single);
        }
        double reach=Math.max(1,player.entityInteractionRange()-.45);
        boolean inReach=player.distanceTo(target)<=reach;
        if(!inReach || !player.hasLineOfSight(target)){
            if(player.getAbilities().flying){Actions.releaseControls();state="holding_flight_for_visible_reach";return false;}
            state="approaching";pursuit.tick(target,reach,controller);return false;
        }
        Actions.releaseControls();Actions.lookAt(target.getEyePosition());
        if(CombatPolicy.canHit(player.hasLineOfSight(target),inReach,bystander&&player.getMainHandItem().getItem() instanceof SwordItem,player.getAttackStrengthScale(0),controller.ticks()-lastSwing)){
            if(firstAttackTick<0)firstAttackTick=controller.ticks();
            mc.gameMode.attack(player,target);player.swing(InteractionHand.MAIN_HAND);lastSwing=controller.ticks();swings++;state="fighting";
        }
        if(lastHealth>=firstHealth && swings>=3){if(++unchanged>40)throw new IllegalStateException("Server has not confirmed damage; stop repeated attacks");}else unchanged=0;
        return false;
    }
    public void cancel(){pursuit.cancel();finish("interrupted_needs_replan");}
}
