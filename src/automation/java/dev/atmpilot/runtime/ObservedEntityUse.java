package dev.atmpilot.runtime;

import com.google.gson.JsonObject;
import dev.atmpilot.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

/** Exactly one ordinary mob-imprisonment interaction with an observed wild Warden. */
public final class ObservedEntityUse implements PilotController.Job {
    /** Name only the already observed own Warden through an ordinary name-tag interaction. */
    public static final class Name implements PilotController.Job {
        private final int id;private final UUID uuid;private final String name;private int phase,wait,ticks;private boolean sent;
        public Name(JsonObject d){id=Jsons.integer(d,"entityId",-1);uuid=UUID.fromString(Jsons.str(d,"uuid",""));name=Jsons.str(d,"name","");if(id<0||name.isBlank()||name.length()>50)throw new IllegalArgumentException("Exact Warden identity and bounded name required");}
        private LivingEntity target(){var e=Actions.mc().level.getEntity(id);if(!(e instanceof LivingEntity l)||!e.isAlive()||!e.getUUID().equals(uuid)||!BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals("minecraft:warden"))throw new IllegalStateException("Own Warden identity changed");ServerRules.checkBuild(e.blockPosition());return l;}
        private boolean tag(ItemStack s){var n=s.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);return BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals("minecraft:name_tag")&&n!=null&&n.getString().equals(name);}
        public String description(){return "Use the prepared name tag once on the observed own Warden";}
        public boolean tick(PilotController c){var mc=Actions.mc();if(++ticks>120)throw new IllegalStateException("Name acknowledgement uncertain; inspect entity and tag before retrying");if(wait-->0)return false;if(mc.screen!=null||!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Close menu and clear cursor before naming");var e=target();if(e.hasCustomName()){if(name.equals(e.getCustomName().getString()))return true;throw new IllegalStateException("Warden already has a different name");}
            if(phase==0){int slot=-1;for(int i=0;i<36;i++)if(tag(mc.player.getInventory().getItem(i))){slot=i;break;}if(slot<0)throw new IllegalStateException("Prepared named tag absent");if(slot>=9){Actions.click(mc.player.inventoryMenu.containerId,slot,8,ClickType.SWAP);slot=8;}Actions.select(slot);phase=1;wait=6;return false;}
            if(phase==1){if(!tag(mc.player.getMainHandItem())||mc.player.distanceTo(e)>mc.player.entityInteractionRange()||!mc.player.hasLineOfSight(e))throw new IllegalStateException("Naming requires prepared tag and ordinary visible reach");Actions.lookAt(e.getEyePosition());phase=2;wait=6;return false;}
            if(!sent){if(!tag(mc.player.getMainHandItem())||mc.player.distanceTo(e)>mc.player.entityInteractionRange()||!mc.player.hasLineOfSight(e))throw new IllegalStateException("Warden reach changed before name click");sent=true;mc.gameMode.interact(mc.player,e,InteractionHand.MAIN_HAND);mc.player.swing(InteractionHand.MAIN_HAND);wait=8;}return false;
        }
    }
    private final int id;private final UUID uuid;private int phase,wait,ticks;private boolean sent;
    public ObservedEntityUse(JsonObject d){id=Jsons.integer(d,"entityId",-1);uuid=UUID.fromString(Jsons.str(d,"uuid",""));if(id<0||!Jsons.str(d,"expectedType","").equals("minecraft:warden"))throw new IllegalArgumentException("Exact observed Warden identity is required");}
    private LivingEntity target(){var mc=Actions.mc();var e=mc.level.getEntity(id);if(!(e instanceof LivingEntity l)||!(e instanceof Mob mob)||e instanceof Player||!e.isAlive()||!e.getUUID().equals(uuid)||!BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals("minecraft:warden")||e.hasCustomName()||e.isPassenger()||mob.isNoAi())throw new IllegalStateException("Wild Warden identity changed");ServerRules.checkBuild(e.blockPosition());return l;}
    private boolean full(ItemStack s)throws Exception{return s.getItem().getClass().getName().equals("com.buuz135.industrial.item.MobImprisonmentToolItem")&&(Boolean)s.getItem().getClass().getMethod("containsEntity",ItemStack.class).invoke(s.getItem(),s);}
    public String description(){return "Capture the observed Warden using one normal mob-tool click";}
    public boolean tick(PilotController c)throws Exception{
        var mc=Actions.mc();if(++ticks>200)throw new IllegalStateException("Capture acknowledgement uncertain; inspect tool and entity before retrying");if(wait-->0)return false;
        if(mc.screen!=null||!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Close menus and return cursor before capture");
        if(phase==0){target();int slot=-1;for(int i=0;i<36;i++){var s=mc.player.getInventory().getItem(i);if(BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals("industrialforegoing:mob_imprisonment_tool")&&!full(s)){slot=i;break;}}if(slot<0)throw new IllegalStateException("No empty native mob imprisonment tool");if(slot>=9){Actions.click(mc.player.inventoryMenu.containerId,slot,8,ClickType.SWAP);slot=8;}Actions.select(slot);phase=1;wait=6;return false;}
        if(phase==1){var e=target();if(mc.player.distanceTo(e)>mc.player.entityInteractionRange()||!mc.player.hasLineOfSight(e))throw new IllegalStateException("Warden must be visible within ordinary reach");Actions.lookAt(e.getEyePosition());phase=2;wait=6;return false;}
        if(phase==2){var e=target();var s=mc.player.getMainHandItem();if(!BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals("industrialforegoing:mob_imprisonment_tool")||full(s)||mc.player.distanceTo(e)>mc.player.entityInteractionRange()||!mc.player.hasLineOfSight(e))throw new IllegalStateException("Tool or reach changed before capture");sent=true;mc.gameMode.interact(mc.player,e,InteractionHand.MAIN_HAND);mc.player.swing(InteractionHand.MAIN_HAND);phase=3;wait=8;return false;}
        if(sent){for(int i=0;i<36;i++){var s=mc.player.getInventory().getItem(i);if(full(s)&&"minecraft:warden".equals(s.getItem().getClass().getMethod("getID",ItemStack.class).invoke(s.getItem(),s))&&(mc.level.getEntity(id)==null||!mc.level.getEntity(id).isAlive()))return true;}}
        return false;
    }
}
