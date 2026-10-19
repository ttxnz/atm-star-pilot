package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** FTB's ordinary claim-manager protocol, restricted to complete approved chunks and never admin mode. */
public final class ChunkAccess {
    private ChunkAccess(){}
    private static Class<?> api(String name)throws ClassNotFoundException{return Class.forName(name,true,PilotJobs.class.getClassLoader());}
    private static Object call(Object target,String name)throws Exception{return target.getClass().getMethod(name).invoke(target);}
    private static Object gui()throws Exception{
        var screen=Actions.mc().screen;if(!api("dev.ftb.mods.ftblibrary.ui.ScreenWrapper").isInstance(screen))throw new IllegalStateException("Open the normal FTB claim manager first");Object gui=call(screen,"getGui");
        if(!api("dev.ftb.mods.ftbchunks.client.gui.ChunkScreen").isInstance(gui)||call(gui,"getOpenedAs")!=null)throw new IllegalStateException("Only the normal own-team claim manager is supported");Object panel=call(gui,"getChunkScreen");if(panel.getClass().getField("isAdminEnabled").getBoolean(panel))throw new IllegalStateException("Admin claim mode is not permitted");
        Object dimension=call(gui,"getDimension");if(!dimension.getClass().getField("dimension").get(dimension).equals(Actions.mc().level.dimension()))throw new IllegalStateException("Claim-manager dimension differs from the current world");return gui;
    }
    private static Object xz(int x,int z)throws Exception{return api("dev.ftb.mods.ftblibrary.math.XZ").getConstructor(int.class,int.class).newInstance(x,z);}
    private static Object record(Object gui,int x,int z)throws Exception{
        Object dimension=call(gui,"getDimension"),manager=call(dimension,"getManager"),lock=manager.getClass().getField("lock").get(manager);
        synchronized(lock){Map<?,?> regions=(Map<?,?>)call(dimension,"getRegions");Object region=regions.get(xz(Math.floorDiv(x,32),Math.floorDiv(z,32)));if(region==null||!(Boolean)call(region,"isDataLoaded"))throw new IllegalStateException("FTB claim map data is not received");Object value=region.getClass().getMethod("getMapChunk",api("dev.ftb.mods.ftblibrary.math.XZ")).invoke(region,xz(Math.floorMod(x,32),Math.floorMod(z,32)));if(value==null||((Number)call(value,"getVersion")).intValue()==0)throw new IllegalStateException("FTB claim map record is unknown");return value;}
    }
    private static boolean own(Object record)throws Exception{return (Boolean)record.getClass().getMethod("isTeamMember",net.minecraft.world.entity.player.Player.class).invoke(record,Actions.mc().player);}
    private static boolean claimed(Object record)throws Exception{return ((Optional<?>)call(record,"getTeam")).isPresent()||((Optional<?>)call(record,"getClaimedDate")).isPresent();}
    private static boolean loaded(Object record)throws Exception{return ((Optional<?>)call(record,"getForceLoadedDate")).isPresent();}
    private static JsonObject limits()throws Exception{Class<?> type=api("dev.ftb.mods.ftbchunks.client.FTBChunksClient");Object instance=type.getField("INSTANCE").get(null),data=call(instance,"getGeneralChunkData");if(data==null)throw new IllegalStateException("Server chunk quotas have not been received");return Jsons.obj("claimed",call(data,"claimed"),"loaded",call(data,"loaded"),"maxClaimChunks",call(data,"maxClaimChunks"),"maxForceLoadChunks",call(data,"maxForceLoadChunks"));}
    private static JsonArray targets(JsonObject data){if(data.has("chunks")){var rows=data.getAsJsonArray("chunks").deepCopy();if(rows.isEmpty()||rows.size()>64)throw new IllegalArgumentException("Expected 1..64 chunk coordinates");return rows;}var p=Actions.mc().player.chunkPosition();var rows=new JsonArray();rows.add(Jsons.obj("x",p.x,"z",p.z));return rows;}
    private static JsonObject inspection(Object gui,JsonArray targets)throws Exception{
        JsonArray rows=new JsonArray();Object panel=call(gui,"getChunkScreen");Collection<?> widgets=(Collection<?>)api("dev.ftb.mods.ftblibrary.ui.Panel").getMethod("getWidgets").invoke(panel);Class<?> widgetApi=api("dev.ftb.mods.ftblibrary.ui.Widget");
        for(var value:targets){var coordinate=value.getAsJsonObject();int x=Jsons.integer(coordinate,"x",0),z=Jsons.integer(coordinate,"z",0);Object r=record(gui,x,z);JsonObject row=Jsons.obj("x",x,"z",z,"claimed",claimed(r),"ownTeam",own(r),"forceLoaded",loaded(r));
            for(Object widget:widgets)if(widget.getClass().getName().endsWith("ChunkScreenPanel$ChunkButton")){var getter=widget.getClass().getMethod("getChunkPos");if(!getter.trySetAccessible())throw new IllegalStateException("Cannot read visible chunk-button coordinates");Object p=getter.invoke(widget);if(((Number)call(p,"x")).intValue()==x&&((Number)call(p,"z")).intValue()==z){row.addProperty("guiX",((Number)widgetApi.getMethod("getX").invoke(widget)).intValue());row.addProperty("guiY",((Number)widgetApi.getMethod("getY").invoke(widget)).intValue());break;}}
            rows.add(row);
        }return Jsons.obj("observedAt",Instant.now().toString(),"dimension",Actions.mc().level.dimension().location().toString(),"limits",limits(),"chunks",rows,"adminRequested",false,"teamOverride",false);
    }
    private static void save(String name,JsonObject value)throws Exception{Files.writeString(PilotMod.CONTROLLER.directory.resolve(name),Jsons.GSON.toJson(value),StandardCharsets.UTF_8);}
    public static final class Open implements PilotController.Job {
        private final JsonObject data;private int elapsed;public Open(JsonObject data){this.data=data.deepCopy();}
        public String description(){return "Open the normal own-team FTB claim manager";}
        public boolean tick(PilotController c)throws Exception{if(elapsed++==0){Actions.closeMenu();api("dev.ftb.mods.ftbchunks.client.gui.ChunkScreen").getMethod("openChunkScreen").invoke(null);return false;}if(elapsed<10)return false;save("last-chunk-inspection.json",inspection(gui(),targets(data)));return true;}
    }
    public static final class Inspect implements PilotController.Job {
        private final JsonObject data;public Inspect(JsonObject data){this.data=data.deepCopy();}
        public String description(){return "Read visible current-dimension FTB ownership and loading";}
        public boolean tick(PilotController c)throws Exception{save("last-chunk-inspection.json",inspection(gui(),targets(data)));return true;}
    }
    public static final class ClaimLoad implements PilotController.Job {
        private final JsonObject data;private final boolean forceLoad;private JsonArray chunks;private int phase,elapsed;private boolean initialized;
        public ClaimLoad(JsonObject data){this.data=data.deepCopy();forceLoad=Jsons.bool(data,"forceLoad",true);}
        public String description(){return "Claim and force-load only complete approved own chunks (phase "+phase+")";}
        private void send(String action,Set<Object> positions)throws Exception{
            if(positions.isEmpty())return;Class<?> op=api("dev.ftb.mods.ftbchunks.net.RequestChunkChangePacket$ChunkChangeOp"),packet=api("dev.ftb.mods.ftbchunks.net.RequestChunkChangePacket");Object request=packet.getConstructor(op,Set.class,boolean.class,Optional.class).newInstance(op.getField(action).get(null),positions,false,Optional.empty());
            save("last-chunk-change.json",Jsons.obj("observedAt",Instant.now().toString(),"state","request_intent","action",action,"chunks",chunks,"adminRequested",false,"teamOverride",false));api("dev.architectury.networking.NetworkManager").getMethod("sendToServer",CustomPacketPayload.class).invoke(null,request);
        }
        public boolean tick(PilotController c)throws Exception{
            Object gui=gui();if(++elapsed>300)throw new IllegalStateException("Chunk change not confirmed; inspect ownership/loading before retrying");if(!initialized){chunks=targets(data);for(var value:chunks){var p=value.getAsJsonObject();int x=Jsons.integer(p,"x",0),z=Jsons.integer(p,"z",0),y=Actions.mc().player.blockPosition().getY();for(int dx:new int[]{0,15})for(int dz:new int[]{0,15})ServerRules.checkBuild(new BlockPos(Math.addExact(Math.multiplyExact(x,16),dx),y,Math.addExact(Math.multiplyExact(z,16),dz)));}initialized=true;}
            JsonObject view=inspection(gui,chunks),quota=view.getAsJsonObject("limits");Set<Object> unclaimed=new LinkedHashSet<>(),unloaded=new LinkedHashSet<>();
            for(var value:view.getAsJsonArray("chunks")){var row=value.getAsJsonObject();if(!row.has("guiX"))throw new IllegalStateException("Requested chunk is not visible in the ordinary claim-manager grid");int x=Jsons.integer(row,"x",0),z=Jsons.integer(row,"z",0);if(Jsons.bool(row,"claimed",false)&&!Jsons.bool(row,"ownTeam",false))throw new IllegalStateException("Never alter another team's chunk");if(!Jsons.bool(row,"claimed",false))unclaimed.add(xz(x,z));if(!Jsons.bool(row,"forceLoaded",false))unloaded.add(xz(x,z));}
            if(phase==0){if(Jsons.integer(quota,"claimed",0)+unclaimed.size()>Jsons.integer(quota,"maxClaimChunks",0)||forceLoad&&Jsons.integer(quota,"loaded",0)+unloaded.size()>Jsons.integer(quota,"maxForceLoadChunks",0))throw new IllegalStateException("Normal server chunk quota is insufficient");send("CLAIM",unclaimed);phase=1;return false;}
            if(phase==1){if(!unclaimed.isEmpty())return false;if(forceLoad)send("LOAD",unloaded);phase=2;return false;}
            if(!unclaimed.isEmpty()||forceLoad&&!unloaded.isEmpty())return false;view.addProperty("state","completed_verified");view.addProperty("forcedLoadingRequested",forceLoad);save("last-chunk-change.json",view);save("last-chunk-inspection.json",view);return true;
        }
    }
}
