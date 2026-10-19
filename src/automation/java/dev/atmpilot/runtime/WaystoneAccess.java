package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Existing user-authorized waystones only. Read the native menu, then click its real travel button once. */
public final class WaystoneAccess {
    private WaystoneAccess(){}
    private static Object call(Object value,String method)throws Exception{return value.getClass().getMethod(method).invoke(value);}
    private static Object field(Object value,String name)throws Exception{
        for(Class<?> type=value.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(value);}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    private static Object menu(){var m=Actions.mc().player.containerMenu;if(!m.getClass().getName().equals("net.blay09.mods.waystones.menu.WaystoneSelectionMenu"))throw new IllegalStateException("Open the native existing-waystone selection menu first");return m;}
    private static JsonObject row(Object w)throws Exception{
        BlockPos p=(BlockPos)call(w,"getPos");return Jsons.obj("uid",call(w,"getWaystoneUid").toString(),"name",((Component)call(w,"getName")).getString(),"effectiveName",((Component)call(w,"getEffectiveName")).getString(),"dimension",((ResourceKey<?>)call(w,"getDimension")).location().toString(),"x",p.getX(),"y",p.getY(),"z",p.getZ(),"valid",call(w,"isValid"));
    }
    private static void save(String file,JsonObject report)throws Exception{Files.writeString(PilotMod.CONTROLLER.directory.resolve(file),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);}
    public static final class Open implements PilotController.Job {
        private final BlockPos pos;private int elapsed;private boolean sent;
        public Open(JsonObject d){pos=Actions.pos(d);}
        public String description(){return "Open user-authorized existing waystone";}
        public boolean tick(PilotController c)throws Exception{
            var mc=Actions.mc();if(sent&&mc.player.containerMenu.getClass().getName().equals("net.blay09.mods.waystones.menu.WaystoneSelectionMenu"))return true;
            if(++elapsed>160)throw new IllegalStateException("Existing waystone did not open; do not repeat before observing");
            if(sent)return false;
            Actions.closeMenu();Actions.checkReach(pos);
            if(!mc.player.getMainHandItem().isEmpty()||!mc.player.getOffhandItem().isEmpty()||mc.player.isShiftKeyDown())throw new IllegalStateException("Use the existing waystone with empty hands and no sneaking");
            String id=BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString();boolean authorized=false;
            var rows=ServerRules.snapshot().getAsJsonArray("verifiedWaystoneSites");if(rows!=null)for(var v:rows){var r=v.getAsJsonObject();if(Jsons.str(r,"dimension","").equals(mc.level.dimension().location().toString())&&Actions.pos(r).equals(pos)&&Jsons.str(r,"block","").equals(id)&&Jsons.bool(r,"travelOnly",false))authorized=true;}
            if(!authorized||!id.startsWith("waystones:"))throw new IllegalStateException("Waystone site must be observed and explicitly recorded for travel only");
            Vec3 point=Vec3.atCenterOf(pos);var hit=mc.level.clip(new ClipContext(mc.player.getEyePosition(),point,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
            if(hit.getType()!=HitResult.Type.BLOCK||!hit.getBlockPos().equals(pos))throw new IllegalStateException("Existing waystone is not visibly reachable");
            Actions.lookAt(hit.getLocation());mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);mc.player.swing(InteractionHand.MAIN_HAND);sent=true;return false;
        }
    }
    public static final class Inspect implements PilotController.Job {
        public String description(){return "Read native existing-waystone destinations";}
        public boolean tick(PilotController c)throws Exception{
            Object m=menu();JsonArray entries=new JsonArray();for(Object w:(Collection<?>)call(m,"getWaystones"))entries.add(row(w));
            save("last-waystone-inspection.json",Jsons.obj("observedAt",Instant.now().toString(),"menuId",Actions.mc().player.containerMenu.containerId,"source",row(call(m,"getWaystoneFrom")),"destinations",entries));return true;
        }
    }
    public static final class Travel implements PilotController.Job {
        private final String uid,expectedDimension;private Object nativeMenu;private JsonObject destination;private boolean sent;private int elapsed,stable;
        public Travel(JsonObject d){uid=Jsons.str(d,"uid","");UUID.fromString(uid);expectedDimension=Jsons.str(d,"expectedDimension","");if(!expectedDimension.equals("minecraft:the_end"))throw new IllegalArgumentException("Current user authorization selects the End destination only");}
        public String description(){return "Authorized survival travel: existing End waystone";}
        public boolean tick(PilotController c)throws Exception{
            var mc=Actions.mc();if(++elapsed>1200)throw new IllegalStateException("Waystone arrival not verified; click was not repeated");
            if(sent){boolean arrived=mc.level.dimension().location().toString().equals(expectedDimension)&&mc.screen==null&&LoadedWorld.has(mc.player.blockPosition())&&mc.player.position().distanceTo(Vec3.atCenterOf(Actions.pos(destination)))<32;stable=arrived?stable+1:0;if(stable>=12){save("last-waystone-travel.json",Jsons.obj("state","arrived_verified","observedAt",Instant.now().toString(),"destination",destination,"player",Jsons.obj("x",mc.player.getX(),"y",mc.player.getY(),"z",mc.player.getZ())));return true;}return false;}
            nativeMenu=menu();Object selected=null;for(Object w:(Collection<?>)call(nativeMenu,"getWaystones"))if(call(w,"getWaystoneUid").toString().equals(uid)){if(selected!=null)throw new IllegalStateException("Duplicate destination UID");selected=w;}
            if(selected==null)throw new IllegalStateException("Observed destination is absent from the live native list");destination=row(selected);
            if(!Jsons.str(destination,"dimension","").equals(expectedDimension)||!Jsons.bool(destination,"valid",false))throw new IllegalStateException("Observed waystone destination does not match the authorized End");
            if(mc.screen==null||!mc.screen.getClass().getName().equals("net.blay09.mods.waystones.client.gui.screen.WaystoneSelectionScreen"))throw new IllegalStateException("Waystone travel screen changed");
            Object list=field(mc.screen,"waystoneList");AbstractWidget target=null;
            for(Object entry:(List<?>)call(list,"children")){
                Object b=field(entry,"waystoneButton");if(call(field(b,"waystone"),"getWaystoneUid").toString().equals(uid)){target=(AbstractWidget)b;break;}
            }
            if(target==null||!target.visible||!target.active)throw new IllegalStateException("Native destination travel button is not available");
            int y=target.getY()+target.getHeight()/2,x=target.getX()+target.getWidth()/2;
            if(y<0||y>=mc.getWindow().getGuiScaledHeight()||x<0||x>=mc.getWindow().getGuiScaledWidth())throw new IllegalStateException("Scroll the observed native destination into view first");
            save("last-waystone-travel.json",Jsons.obj("state","click_intent","observedAt",Instant.now().toString(),"destination",destination,"menuId",mc.player.containerMenu.containerId));
            var screen=mc.screen;if(!screen.mouseClicked(x,y,0))throw new IllegalStateException("Native destination button did not receive the click");if(mc.screen==screen)screen.mouseReleased(x,y,0);sent=true;return false;
        }
    }
}
