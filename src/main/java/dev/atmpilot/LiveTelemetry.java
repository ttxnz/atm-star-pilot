package dev.atmpilot;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.*;
import java.time.Instant;
import java.util.Comparator;

/** Small live response: no full inventory/component, recipe catalog, or machine-menu serialization. */
public final class LiveTelemetry {
    private LiveTelemetry(){}
    public static JsonObject read(PilotController controller,int radius){
        if(radius<1||radius>64)throw new IllegalArgumentException("Live radius must be 1..64");
        var mc=Minecraft.getInstance();var result=Jsons.obj("observedAt",Instant.now().toString(),"clientTick",controller.ticks(),
            "connected",mc.player!=null&&mc.level!=null,"controller",controller.status(),"survivalReflex",SurvivalReflex.status(),"deathRecovery",DeathRecovery.status(),"localCombat",RealtimeCombat.status());
        if(mc.player==null||mc.level==null)return result;
        var p=mc.player;var v=p.getDeltaMovement();
        result.add("player",Jsons.obj("uuid",p.getUUID().toString(),"x",p.getX(),"y",p.getY(),"z",p.getZ(),"yaw",p.getYRot(),"pitch",p.getXRot(),
            "health",p.getHealth(),"food",p.getFoodData().getFoodLevel(),"alive",p.isAlive(),"dimension",mc.level.dimension().location().toString(),
            "flying",p.getAbilities().flying,"onGround",p.onGround(),"velocity",Jsons.obj("x",v.x,"y",v.y,"z",v.z),"selected",p.getInventory().selected,
            "heldItem",BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString(),"screen",mc.screen==null?null:mc.screen.getClass().getName(),
            "paused",mc.isPaused(),"windowActive",mc.isWindowActive(),"inputForward",p.input.forwardImpulse,"inputLeft",p.input.leftImpulse,
            "forwardKeyDown",mc.options.keyUp.isDown(),"forwardBinding",mc.options.keyUp.getKey().getName(),"sneakKeyDown",mc.options.keyShift.isDown()));
        result.add("menu",Jsons.obj("id",p.containerMenu.containerId,"stateId",p.containerMenu.getStateId(),"carriedCount",p.containerMenu.getCarried().getCount()));
        var biomeKey=mc.level.getBiome(p.blockPosition()).unwrapKey();
        result.getAsJsonObject("player").addProperty("biome",biomeKey.isPresent()?biomeKey.get().location().toString():"unregistered");
        var all=mc.level.getEntities(p,p.getBoundingBox().inflate(radius),e->e instanceof LivingEntity);
        all.sort(Comparator.comparingDouble(p::distanceTo));JsonArray entities=new JsonArray();
        for(var e:all.stream().limit(64).toList()){
            var living=(LivingEntity)e;entities.add(Jsons.obj("entityId",e.getId(),"uuid",e.getUUID().toString(),"type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
                "x",e.getX(),"y",e.getY(),"z",e.getZ(),"health",living.getHealth(),"alive",e.isAlive(),"distance",p.distanceTo(e),
                "named",e.hasCustomName(),"visible",p.hasLineOfSight(e),"meleePermitted",MeleeFighter.permitted(living)));
        }
        result.add("entities",entities);result.addProperty("entitiesTruncated",all.size()>64);return result;
    }
}
