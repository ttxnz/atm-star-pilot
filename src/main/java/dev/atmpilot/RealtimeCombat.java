package dev.atmpilot;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import java.util.*;

/** Runs on the client tick even while the external planner is thinking. Pauses the current job in memory. */
public final class RealtimeCombat {
    private RealtimeCombat(){}
    private static MeleeFighter fighter;
    private static long cooldown,scans,intercepted;
    private static String reason="No nearby threat";
    public static boolean active(){return fighter!=null;}
    public static void cancel(){clear();}
    public static JsonObject status(){return Jsons.obj("active",active(),"reason",reason,"scans",scans,"interceptedTicks",intercepted,"fight",fighter==null?null:fighter.status());}
    private static void clear(){if(fighter!=null){fighter.cancel();fighter=null;}}
    public static boolean tick(PilotController controller){
        var mc=Minecraft.getInstance();
        try{
            if(mc.player==null || mc.level==null){fighter=null;return false;}
            var session=SurvivalReflex.status();
            if(Jsons.bool(session,"manualHold",false)||Jsons.bool(DeathRecovery.status(),"manualHold",false)||!Jsons.bool(session,"armed",false)||!mc.player.isAlive()||DeathRecovery.active(controller)){
                clear();reason="Manual control, inactive session, or recovery";return false;
            }
            var guard=ServerRules.snapshot().getAsJsonObject("survivalGuard");
            if(guard==null || !Jsons.bool(guard,"defendHostiles",false)){clear();reason="Local defense disabled";return false;}
            var damage=mc.player.getLastDamageSource();LivingEntity attacker=damage!=null&&damage.getEntity() instanceof LivingEntity living&&MeleeFighter.permitted(living)?living:null;
            if(mc.screen!=null||mc.player.isUsingItem()){
                if(attacker==null||!Jsons.bool(guard,"counterAttackOnDamage",false)||!mc.player.containerMenu.getCarried().isEmpty()){clear();return false;}
                Actions.closeMenu();if(mc.player.isUsingItem())mc.gameMode.releaseUsingItem(mc.player);
            }
            if(!mc.player.containerMenu.getCarried().isEmpty()){clear();return false;}
            if(controller.ticks()<cooldown&&attacker==null)return false;
            List<LivingEntity> threats=new ArrayList<>();boolean creeper=false;scans++;
            for(var e:mc.level.getEntities(mc.player,mc.player.getBoundingBox().inflate(5))){
                if(!(e instanceof LivingEntity living)||!e.isAlive()||!mc.player.hasLineOfSight(e))continue;
                String type=BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                if(type.equals("minecraft:creeper")&&mc.player.distanceTo(e)<=4)creeper=true;
                if(MeleeFighter.permitted(living)&&mc.player.distanceTo(e)<=4.5&&(!mc.player.getAbilities().flying||mc.player.distanceTo(e)<=mc.player.entityInteractionRange()-.45))threats.add(living);
            }
            if(CombatPolicy.shouldEscape(mc.player.getHealth(),Math.min(1,Jsons.decimal(guard,"healthThreshold",1)),creeper,threats.size())){
                if(creeper||!threats.isEmpty()||fighter!=null){clear();reason="Danger exceeds local defense; verified safety return";controller.stop("Survival reflex: "+reason);cooldown=controller.ticks()+100;return true;}
                return false;
            }
            if(fighter==null){
                var nearest=threats.stream().min(Comparator.comparingDouble(mc.player::distanceTo)).orElse(null);
                if(attacker!=null&&threats.contains(attacker))nearest=attacker;
                if(nearest==null){reason="Watching for attackers and blocking mobs";return false;}
                fighter=new MeleeFighter(nearest.getId(),nearest.getUUID(),BuiltInRegistries.ENTITY_TYPE.getKey(nearest.getType()).toString());
                reason=nearest==attacker?"Damage source identified; immediate local counterattack":"Local hostile or blocking mob; existing action paused";
            }
            intercepted++;
            if(fighter.tick(controller)){fighter=null;cooldown=controller.ticks()+2;reason="Target death verified; action checkpoint resumes";Actions.releaseControls();}
            return true;
        }catch(Exception|LinkageError error){
            clear();cooldown=controller.ticks()+100;reason="Local defense stopped: "+error.getMessage();
            controller.stop("Survival reflex: "+reason);return true;
        }
    }
}
