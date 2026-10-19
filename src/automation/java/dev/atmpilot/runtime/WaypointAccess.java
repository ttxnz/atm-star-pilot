package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.resources.*;
import net.minecraft.world.level.Level;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Personal FTB waypoints and the installed map's normal permission-checked teleport action. No chat commands or permission changes. */
public final class WaypointAccess {
    private WaypointAccess(){}
    private static Class<?> api(String n)throws Exception{return Class.forName(n,true,PilotJobs.class.getClassLoader());}
    private static Object call(Object o,String n)throws Exception{return o.getClass().getMethod(n).invoke(o);}
    public static boolean available(){return Jsons.bool(ServerRules.snapshot(),"allowNativeMapTeleport",false)&&Actions.mc().player.hasPermissions(2);}
    private static JsonObject point(String name){
        var rules=ServerRules.snapshot();var rows=rules.getAsJsonArray("verifiedWaypoints");if(rows!=null)for(var v:rows){var r=v.getAsJsonObject();if(!Jsons.str(r,"name","").equals(name))continue;
            BlockPos p=Actions.pos(r);String dimension=Jsons.str(r,"dimension","");boolean approved=false;
            for(var a:rules.getAsJsonArray("buildAreas")){var box=a.getAsJsonObject();if(Jsons.str(box,"dimension","").equals(dimension)&&Jsons.bool(box,"surveyed",false)&&Jsons.bool(box,"claimsChecked",false)&&p.getX()>=Jsons.integer(box,"minX",Integer.MAX_VALUE)&&p.getX()<=Jsons.integer(box,"maxX",Integer.MIN_VALUE)&&p.getZ()>=Jsons.integer(box,"minZ",Integer.MAX_VALUE)&&p.getZ()<=Jsons.integer(box,"maxZ",Integer.MIN_VALUE)&&p.getY()>=Jsons.integer(box,"minY",Integer.MAX_VALUE)&&p.getY()+2<=Jsons.integer(box,"maxY",Integer.MIN_VALUE))approved=true;}
            if(!approved&&Jsons.bool(r,"storageAccessOnly",false)&&Jsons.bool(rules,"allowSharedStorageWithdraw",false)){
                var base=rules.getAsJsonObject("sharedBase");var box=base.getAsJsonObject("conservativeBounds");approved=dimension.equals(Jsons.str(base,"dimension",""))&&p.getX()>=Jsons.integer(box,"minX",Integer.MAX_VALUE)&&p.getX()<=Jsons.integer(box,"maxX",Integer.MIN_VALUE)&&p.getZ()>=Jsons.integer(box,"minZ",Integer.MAX_VALUE)&&p.getZ()<=Jsons.integer(box,"maxZ",Integer.MIN_VALUE);
            }
            if(!approved&&Jsons.bool(r,"travelAccessOnly",false)&&available())approved=true;
            if(!approved||!Jsons.bool(r,"observedSafe",false))throw new IllegalStateException("Waypoint destination must be an observed safe authorized travel location");return r.deepCopy();
        }throw new IllegalArgumentException("No verified personal waypoint: "+name);
    }
    private static ResourceKey<Level> dimension(JsonObject p){return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(Jsons.str(p,"dimension","")));}
    private static Object manager(ResourceKey<Level> d)throws Exception{Object client=api("dev.ftb.mods.ftbchunks.api.FTBChunksAPI").getMethod("clientApi").invoke(null);return ((Optional<?>)api("dev.ftb.mods.ftbchunks.api.client.FTBChunksClientAPI").getMethod("getWaypointManager",ResourceKey.class).invoke(client,d)).orElseThrow(()->new IllegalStateException("Visited dimension's waypoint manager is not available"));}
    private static Collection<?> all(Object m)throws Exception{return (Collection<?>)api("dev.ftb.mods.ftbchunks.api.client.waypoint.WaypointManager").getMethod("getAllWaypoints").invoke(m);}
    private static Object existing(Object m,JsonObject p)throws Exception{for(Object w:all(m))if(call(w,"getName").equals(Jsons.str(p,"name",""))&&call(w,"getPos").equals(Actions.pos(p)))return w;return null;}
    private static void save(String f,JsonObject j)throws Exception{Files.writeString(PilotMod.CONTROLLER.directory.resolve(f),Jsons.GSON.toJson(j),StandardCharsets.UTF_8);}
    private static void verifyNewTravelPoint(JsonObject p){
        if(!Jsons.bool(p,"travelAccessOnly",false))return;
        var mc=Actions.mc();var arrival=Actions.pos(p).above();
        if(!mc.level.dimension().location().toString().equals(Jsons.str(p,"dimension","")))throw new IllegalStateException("Observe a new travel-only point in its current dimension first");
        for(var q:new BlockPos[]{arrival,arrival.above(),arrival.above(2)})if(!LoadedWorld.has(q)||!mc.level.getBlockState(q).getCollisionShape(mc.level,q).isEmpty()||!mc.level.getFluidState(q).isEmpty())throw new IllegalStateException("New travel-only arrival is not loaded, clear and dry");
        if(Jsons.bool(p,"hover",false)){if(!mc.player.getAbilities().flying)throw new IllegalStateException("Existing flight is required for a travel-only hover point");}
        else if(!LoadedWorld.has(arrival.below())||!mc.level.getBlockState(arrival.below()).isFaceSturdy(mc.level,arrival.below(),Direction.UP))throw new IllegalStateException("New travel-only stand is unsupported");
    }
    public static final class Inspect implements PilotController.Job {
        private final String requested;
        public Inspect(JsonObject data){requested=Jsons.str(data,"dimension","");}
        public String description(){return "Read actual native map-teleport permission and personal waypoints";}
        public boolean tick(PilotController c)throws Exception{
            ResourceKey<Level> target=requested.isEmpty()?Actions.mc().level.dimension():ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(requested));
            JsonArray rows=new JsonArray();Object m;
            try{m=manager(target);}catch(IllegalStateException missing){
                save("last-waypoints-inspection.json",Jsons.obj("observedAt",Instant.now().toString(),"dimension",target.location().toString(),"mapAvailable",false,"waypoints",rows));return true;
            }
            for(Object w:all(m)){BlockPos p=(BlockPos)call(w,"getPos");rows.add(Jsons.obj("name",call(w,"getName"),"x",p.getX(),"y",p.getY(),"z",p.getZ(),"deathpoint",call(w,"isDeathpoint")));}
            save("last-waypoints-inspection.json",Jsons.obj("observedAt",Instant.now().toString(),"dimension",target.location().toString(),"mapAvailable",true,"nativeMapTeleportPermission",Actions.mc().player.hasPermissions(2),"authorizedAndAvailable",available(),"waypoints",rows));return true;
        }
    }
    public static final class Add implements PilotController.Job {
        private final String name;public Add(JsonObject d){name=Jsons.str(d,"name","");}
        public String description(){return "Save authorized personal waypoint "+name;}
        public boolean tick(PilotController c)throws Exception{JsonObject p=point(name);verifyNewTravelPoint(p);Object m=manager(dimension(p)),w=existing(m,p);if(w==null)w=api("dev.ftb.mods.ftbchunks.client.FTBChunksClient").getMethod("addWaypoint",String.class,GlobalPos.class,int.class).invoke(null,name,GlobalPos.of(dimension(p),Actions.pos(p)),0x35D6FF);if(w==null||existing(m,p)==null)throw new IllegalStateException("Native personal waypoint was not saved");save("last-waypoint-add.json",Jsons.obj("state","saved_verified","observedAt",Instant.now().toString(),"point",p));return true;}
    }
    public static final class Travel implements PilotController.Job {
        private final String name;private JsonObject target;private boolean sent;private int elapsed,stable;
        public Travel(JsonObject d){name=Jsons.str(d,"name","");}
        public String description(){return "Authorized survival travel: native map waypoint "+name;}
        public boolean tick(PilotController c)throws Exception{
            var mc=Actions.mc();if(++elapsed>600)throw new IllegalStateException("Native map arrival not verified; request was not repeated");
            if(!sent){if(!available())throw new IllegalStateException("Server has not granted the native map's teleport permission");target=point(name);Object m=manager(dimension(target));if(existing(m,target)==null)throw new IllegalStateException("Save and observe the personal waypoint before travelling");if(Jsons.bool(target,"hover",false)&&!mc.player.getAbilities().flying)throw new IllegalStateException("Enable the existing flight before travelling to a hover waypoint");Actions.closeMenu();
                BlockPos arrival=Actions.pos(target).above();if(mc.level.dimension().equals(dimension(target))&&LoadedWorld.has(arrival)&&(!mc.level.getBlockState(arrival).getCollisionShape(mc.level,arrival).isEmpty()||!mc.level.getBlockState(arrival.above()).getCollisionShape(mc.level,arrival.above()).isEmpty()))throw new IllegalStateException("Observed waypoint arrival became obstructed");
                save("last-waypoint-travel.json",Jsons.obj("state","request_intent","observedAt",Instant.now().toString(),"point",target));Object packet=api("dev.ftb.mods.ftbchunks.net.TeleportFromMapPacket").getConstructor(BlockPos.class,boolean.class,ResourceKey.class).newInstance(arrival,false,dimension(target));api("dev.architectury.networking.NetworkManager").getMethod("sendToServer",CustomPacketPayload.class).invoke(null,packet);sent=true;return false;
            }
            boolean arrived=mc.level.dimension().equals(dimension(target))&&LoadedWorld.has(mc.player.blockPosition())&&mc.player.position().distanceTo(Vec3.atBottomCenterOf(Actions.pos(target).above()))<2;stable=arrived?stable+1:0;if(stable>=10){save("last-waypoint-travel.json",Jsons.obj("state","arrived_verified","observedAt",Instant.now().toString(),"point",target,"x",mc.player.getX(),"y",mc.player.getY(),"z",mc.player.getZ()));return true;}return false;
        }
    }
}
