package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Native Shrink UI buttons and single ordinary entity interactions; no capture/spawn setters. */
public final class ShrinkTools {
    private static final Set<String> ANIMALS=Set.of("minecraft:chicken","minecraft:cow","minecraft:pig","minecraft:sheep");
    private static Class<?> api(String name)throws Exception{return Class.forName(name,true,PilotJobs.class.getClassLoader());}
    private static String id(ItemStack s){return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();}
    private static boolean canBottle(LivingEntity e)throws Exception{return (Boolean)api("net.gigabit101.shrink.api.ShrinkAPI").getMethod("canCaptureEntity",LivingEntity.class).invoke(null,e);}
    private static double modifier(ItemStack s)throws Exception{
        Object supplier=api("net.gigabit101.shrink.init.ShrinkComponentTypes").getField("SHRINKING_DEVICE").get(null);
        Object type=api("dev.architectury.registry.registries.RegistrySupplier").getMethod("get").invoke(supplier);
        Object value=s.get((DataComponentType<?>)type);return value==null?0:((Number)value).doubleValue();
    }
    private static void save(String file,JsonObject value)throws Exception{value.addProperty("observedAt",Instant.now().toString());Files.writeString(PilotMod.CONTROLLER.directory.resolve(file),Jsons.GSON.toJson(value),StandardCharsets.UTF_8);}
    private static void select(String wanted){var mc=Actions.mc();
        for(int i=0;i<36;i++)if(id(mc.player.getInventory().getItem(i)).equals(wanted)){
            if(i>=9){Actions.click(mc.player.inventoryMenu.containerId,i,8,ClickType.SWAP);Actions.select(8);}else Actions.select(i);return;
        }throw new IllegalStateException("Missing normal interaction item: "+wanted);
    }
    public static final class Configure implements PilotController.Job {
        private final double target;private int clicks,wait,elapsed;private Object menu,provider;
        public Configure(JsonObject d){double scale=Jsons.decimal(d,"scale",.5);if(!Double.isFinite(scale)||scale<.1||scale>.9||Math.abs(scale*10-Math.rint(scale*10))>.0001)throw new IllegalArgumentException("Choose a native tenths scale within .1.. .9");target=scale-1;}
        public String description(){return "Configure the open native shrinking-device size buttons";}
        public boolean tick(PilotController c)throws Exception{
            var mc=Actions.mc();ServerRules.checkBuild(mc.player.blockPosition());ServerRules.checkScreenAction();
            if(mc.screen==null||!mc.screen.getClass().getName().equals("net.creeperhost.polylib.client.modulargui.ModularGuiContainer")||!mc.player.containerMenu.getClass().getName().equals("net.gigabit101.shrink.ShrinkingDeviceContainer")||!id(mc.player.getMainHandItem()).equals("shrink:shrinking_device")||!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Open the own held shrinking device's normal GUI with an empty cursor");
            if(menu==null){menu=mc.player.containerMenu;Object gui=mc.screen.getClass().getMethod("getModularGui").invoke(mc.screen);provider=gui.getClass().getMethod("getProvider").invoke(gui);if(!provider.getClass().getName().equals("net.gigabit101.shrink.client.ShrinkScreen"))throw new IllegalStateException("Unexpected native Shrink GUI provider");}
            if(menu!=mc.player.containerMenu)throw new IllegalStateException("Shrinking device menu changed");
            if(++elapsed>160)throw new IllegalStateException("Native device-size change was not verified");if(wait-->0)return false;
            double current=modifier(mc.player.getMainHandItem());
            if(Math.abs(current-target)<.0001){save("last-shrink-configuration.json",Jsons.obj("state","completed_verified","modifier",current,"scale",1+current,"nativeButtonClicks",clicks,"device",WorldView.stack(mc.player.getMainHandItem())));return true;}
            if(clicks++>=12||net.minecraft.client.gui.screens.Screen.hasShiftDown())throw new IllegalStateException("Native size buttons did not converge or physical Shift is held");
            provider.getClass().getMethod("onButtonPress",boolean.class).invoke(provider,current<target);wait=8;return false;
        }
    }
    public static final class Inspect implements PilotController.Job {
        private final int radius;public Inspect(JsonObject d){radius=Jsons.integer(d,"radius",32);if(radius<1||radius>64)throw new IllegalArgumentException("Animal observation radius must be 1..64");}
        public String description(){return "Read visible passive animals and exact identities";}
        public boolean tick(PilotController c)throws Exception{var mc=Actions.mc();JsonArray animals=new JsonArray();
            for(Entity e:mc.level.getEntities(mc.player,mc.player.getBoundingBox().inflate(radius)))if(e instanceof Animal a&&ANIMALS.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString())){
                animals.add(Jsons.obj("entityId",e.getId(),"uuid",e.getUUID().toString(),"type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),"x",e.getX(),"y",e.getY(),"z",e.getZ(),"health",a.getHealth(),"customName",e.hasCustomName(),"leashed",a.isLeashed(),"scale",a.getAttributeValue(Attributes.SCALE),"canBottle",canBottle(a)));
            }save("last-animal-inspection.json",Jsons.obj("dimension",mc.level.dimension().location().toString(),"animals",animals));return true;
        }
    }
    public static final class Capture implements PilotController.Job,ReflexResumable {
        private final int entityId;private final UUID uuid;private final String type;
        private int phase,wait,elapsed,emptyBefore,filledBefore;private boolean shrinkSent,bottleSent;private String dimension;
        private final AnimalApproach localApproach=new AnimalApproach();private JsonObject sourceReport;
        private int actualEntityId,ackDeadline;private long firstInteractionTick=-1;private String lastError="";
        public Capture(JsonObject d){entityId=Jsons.integer(d,"entityId",-1);uuid=UUID.fromString(Jsons.str(d,"uuid",""));type=Jsons.str(d,"expectedType","");actualEntityId=entityId;if(entityId<0||!ANIMALS.contains(type))throw new IllegalArgumentException("An observed wild chicken, cow, pig or sheep identity is required");}
        public String description(){return "Shrink and bottle one observed wild animal using ordinary right-clicks";}
        public boolean resumeAfterLocalCombat(){localApproach.afterCombat();return true;}
        private boolean surfaceFacilities(JsonObject report){
            int surface=Jsons.integer(report.getAsJsonObject("bounds"),"surfaceY",0);
            for(var value:report.getAsJsonArray("blockEntities")){
                var block=value.getAsJsonObject();
                if(Jsons.integer(block,"y",surface)>=surface-8&&!Jsons.str(block,"block","").equals("naturesaura:aura_bloom"))return true;
            }
            return false;
        }
        private void source(Entity e)throws Exception{
            if(sourceReport==null)sourceReport=JsonParser.parseString(Files.readString(PilotMod.CONTROLLER.directory.resolve("wild-animal-site-survey.json"),StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject r=sourceReport;
            long age=Duration.between(Instant.parse(Jsons.str(r,"observedAt","")),Instant.now()).toSeconds();var bounds=r.getAsJsonObject("bounds");var claims=r.getAsJsonObject("claims");
            if(age<0||age>900||!Jsons.str(r,"dimension","").equals(Actions.mc().level.dimension().location().toString())||Jsons.integer(r,"unloadedChunks",1)!=0||surfaceFacilities(r)||claims==null||!Jsons.bool(claims,"checked",false)||Jsons.integer(claims,"claimed",1)!=0||Jsons.integer(claims,"unknown",1)!=0)throw new IllegalStateException("A fresh fully loaded, unclaimed wildlife survey without surface facilities is required");
            var p=e.blockPosition();if(p.getX()<Jsons.integer(bounds,"minX",0)||p.getX()>Jsons.integer(bounds,"maxX",-1)||p.getZ()<Jsons.integer(bounds,"minZ",0)||p.getZ()>Jsons.integer(bounds,"maxZ",-1))throw new IllegalStateException("Animal left the surveyed wild collection area");
        }
        private Animal target(boolean reach)throws Exception{var mc=Actions.mc();Entity e=mc.level.getEntity(entityId);
            if(e==null||!uuid.equals(e.getUUID()))e=mc.level.getEntities(mc.player,mc.player.getBoundingBox().inflate(64)).stream().filter(candidate->uuid.equals(candidate.getUUID())).findFirst().orElse(null);
            if(!(e instanceof Animal a)||!e.isAlive()||!uuid.equals(e.getUUID())||!BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(type)||e.hasCustomName()||a.isLeashed())throw new IllegalStateException("Wild animal identity or ownership indicators changed");source(e);
            actualEntityId=e.getId();
            if(reach&&(mc.player.distanceTo(e)>mc.player.entityInteractionRange()||!mc.player.hasLineOfSight(e)))throw new IllegalStateException("Observed animal is outside normal visible interaction reach");return a;
        }
        private int empty(){int n=0;for(int i=0;i<36;i++){ItemStack s=Actions.mc().player.getInventory().getItem(i);if(id(s).equals("minecraft:glass_bottle"))n+=s.getCount();}return n;}
        private int filled()throws Exception{int n=0;for(int i=0;i<36;i++){ItemStack s=Actions.mc().player.getInventory().getItem(i);if(!id(s).equals("shrink:shrink_bottle"))continue;
            Optional<?> contained=(Optional<?>)api("net.gigabit101.shrink.items.components.ShrinkComponentUtils").getMethod("getEntityType",ItemStack.class).invoke(null,s);
            if(contained.isPresent()&&contained.get() instanceof EntityType<?> t&&BuiltInRegistries.ENTITY_TYPE.getKey(t).toString().equals(type))n+=s.getCount();
        }return n;}
        private void report(String state)throws Exception{save("last-animal-capture.json",Jsons.obj("state",state,"dimension",dimension,"entityId",actualEntityId,"originalEntityId",entityId,"uuid",uuid.toString(),"type",type,"phase",phase,"elapsedTicks",elapsed,"clientTick",PilotMod.CONTROLLER.ticks(),"firstInteractionTick",firstInteractionTick,"shrinkClickSentOnce",shrinkSent,"bottleClickSentOnce",bottleSent,"emptyBefore",emptyBefore,"filledBefore",filledBefore,"lastError",lastError));}
        public boolean tick(PilotController c)throws Exception{try{return step(c);}catch(Exception e){lastError=e.toString();report("stopped_needs_replan");throw e;}}
        private boolean step(PilotController c)throws Exception{var mc=Actions.mc();
            if(++elapsed>600)throw new IllegalStateException("Animal capture was not verified; do not repeat blindly");
            if(mc.screen!=null||!mc.player.containerMenu.getCarried().isEmpty()||dimension!=null&&!dimension.equals(mc.level.dimension().location().toString()))throw new IllegalStateException("Capture screen, cursor or dimension changed");if(wait-->0)return false;
            if(dimension==null)dimension=mc.level.dimension().location().toString();
            if(phase<6&&!localApproach.tick(target(false),c))return false;
            if(phase==0){dimension=mc.level.dimension().location().toString();Animal a=target(true);emptyBefore=empty();filledBefore=filled();if(emptyBefore<1)throw new IllegalStateException("A normal empty glass bottle is required");
                if(canBottle(a)){phase=3;ackDeadline=elapsed+60;return false;}select("shrink:shrinking_device");phase=1;wait=1;report("device_selected");return false;
            }
            if(phase==1){Animal a=target(true);Actions.lookAt(a.getEyePosition());phase=2;return false;}
            if(phase==2){Animal a=target(true);ItemStack held=mc.player.getMainHandItem();if(!id(held).equals("shrink:shrinking_device")||modifier(held)>=0||!(Boolean)held.getItem().getClass().getMethod("hasPower",net.minecraft.world.entity.player.Player.class,ItemStack.class).invoke(held.getItem(),mc.player,held))throw new IllegalStateException("Configured charged shrinking device changed");
                if(!canBottle(a)){shrinkSent=true;firstInteractionTick=c.ticks();report("shrink_interaction_intent");mc.gameMode.interact(mc.player,a,InteractionHand.MAIN_HAND);mc.player.swing(InteractionHand.MAIN_HAND);}phase=3;ackDeadline=elapsed+60;return false;
            }
            if(phase==3){Animal a=target(true);if(!canBottle(a)){if(elapsed>ackDeadline)throw new IllegalStateException("Native shrink acknowledgement timed out");return false;}select("minecraft:glass_bottle");phase=4;wait=1;report("bottle_selected_after_verified_shrink");return false;}
            if(phase==4){Animal a=target(true);Actions.lookAt(a.getEyePosition());phase=5;return false;}
            if(phase==5){Animal a=target(true);if(!canBottle(a)||!id(mc.player.getMainHandItem()).equals("minecraft:glass_bottle"))throw new IllegalStateException("Shrunk animal or held bottle changed");
                bottleSent=true;report("bottle_interaction_intent");mc.gameMode.interact(mc.player,a,InteractionHand.MAIN_HAND);mc.player.swing(InteractionHand.MAIN_HAND);phase=6;ackDeadline=elapsed+60;return false;
            }
            if(phase==6){Entity e=mc.level.getEntity(actualEntityId);if(empty()==emptyBefore-1&&filled()==filledBefore+1&&(e==null||!e.isAlive())){report("completed_verified");return true;}if(elapsed>ackDeadline)throw new IllegalStateException("Bottle result was not verified; do not repeat the interaction");return false;}
            throw new IllegalStateException("Invalid animal capture phase");
        }
        public void cancel(){localApproach.cancel();Actions.releaseControls();if(elapsed>0)try{report("interrupted_needs_replan");}catch(Exception ignored){}}
    }
}
