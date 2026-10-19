package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Read-only inspection of loaded world geometry and FTB's existing client map records. */
public final class AreaSurvey implements PilotController.Job {
    private final int minX,maxX,minZ,maxZ,surfaceY;
    AreaSurvey(JsonObject data){
        minX=Jsons.integer(data,"minX",0);maxX=Jsons.integer(data,"maxX",-1);
        minZ=Jsons.integer(data,"minZ",0);maxZ=Jsons.integer(data,"maxZ",-1);surfaceY=Jsons.integer(data,"surfaceY",253);
        long footprint=((long)maxX-minX+1)*((long)maxZ-minZ+1);
        if(minX>maxX||minZ>maxZ||footprint<1||footprint>4096)throw new IllegalArgumentException("Survey footprint must contain 1..4096 columns");
    }
    public String description(){return "Read-only site/claim survey "+minX+","+minZ+" to "+maxX+","+maxZ;}
    public boolean tick(PilotController controller)throws Exception {
        var mc=Actions.mc();
        JsonArray chunks=new JsonArray(),structures=new JsonArray(),occupied=new JsonArray();int unloaded=0,uneven=0,occupiedCount=0;
        for(int cx=Math.floorDiv(minX,16);cx<=Math.floorDiv(maxX,16);cx++)for(int cz=Math.floorDiv(minZ,16);cz<=Math.floorDiv(maxZ,16);cz++){
            BlockPos reference=new BlockPos(cx*16,surfaceY,cz*16);
            var loadedChunk=LoadedWorld.chunk(cx,cz);
            JsonObject chunk=Jsons.obj("x",cx,"z",cz,"loaded",loadedChunk!=null);
            if(loadedChunk==null){unloaded++;chunks.add(chunk);continue;}
            for(var entity:loadedChunk.getBlockEntities().values()){
                BlockPos pos=entity.getBlockPos();if(pos.getX()<minX||pos.getX()>maxX||pos.getZ()<minZ||pos.getZ()>maxZ)continue;
                structures.add(Jsons.obj("x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),
                    "block",BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString()));
            }
            var sections=loadedChunk.getSections();
            for(int sectionIndex=0;sectionIndex<sections.length;sectionIndex++){
                var section=sections[sectionIndex];int baseY=mc.level.getSectionYFromSectionIndex(sectionIndex)*16;
                if(baseY+15<surfaceY||section.hasOnlyAir())continue;
                for(int x=Math.max(minX,cx*16);x<=Math.min(maxX,cx*16+15);x++)
                    for(int z=Math.max(minZ,cz*16);z<=Math.min(maxZ,cz*16+15);z++)for(int y=Math.max(surfaceY,baseY);y<=baseY+15;y++){
                        var state=section.getBlockState(Math.floorMod(x,16),y-baseY,Math.floorMod(z,16));
                        if(state.isAir())continue;occupiedCount++;
                        if(occupied.size()<512)occupied.add(Jsons.obj("x",x,"y",y,"z",z,"block",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
                    }
            }
            chunks.add(chunk);
        }
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){
            BlockPos floor=new BlockPos(x,surfaceY-1,z);
            if(!LoadedWorld.has(floor))continue;
            if(!mc.level.getBlockState(floor).isFaceSturdy(mc.level,floor,net.minecraft.core.Direction.UP)
                ||!mc.level.getBlockState(floor.above()).getCollisionShape(mc.level,floor.above()).isEmpty())uneven++;
        }
        JsonObject claims=claims(chunks);
        JsonObject report=Jsons.obj("observedAt",Instant.now().toString(),"dimension",mc.level.dimension().location().toString(),
            "bounds",Jsons.obj("minX",minX,"maxX",maxX,"minZ",minZ,"maxZ",maxZ,"surfaceY",surfaceY),
            "worldMinY",mc.level.getMinBuildHeight(),"worldMaxY",mc.level.getMaxBuildHeight(),
            "unloadedChunks",unloaded,"nonWalkableColumns",uneven,"blockEntities",structures,
            "nonAirAboveSurface",occupiedCount,"occupiedExamples",occupied,"claims",claims,"chunks",chunks,
            "note","Only client-visible loaded data was read. No claim, block, inventory or machine was modified.");
        Path output=controller.directory.resolve("last-area-survey.json");Files.writeString(output,Jsons.GSON.toJson(report),StandardCharsets.UTF_8);
        return true;
    }
    private JsonObject claims(JsonArray chunks){
        try{
            Class<?> managerType=Class.forName("dev.ftb.mods.ftbchunks.client.map.MapManager");
            Object manager=((Optional<?>)managerType.getMethod("getInstance").invoke(null)).orElseThrow();
            Object lock=managerType.getField("lock").get(manager);
            Class<?> xzType=Class.forName("dev.ftb.mods.ftblibrary.math.XZ");
            Object dimension;
            synchronized(lock){
                Map<?,?> dimensions=(Map<?,?>)managerType.getMethod("getDimensions").invoke(manager);
                dimension=dimensions.get(Actions.mc().level.dimension());
                if(dimension==null)return Jsons.obj("available",true,"checked",false,"reason","Current dimension has no existing map record");
                Map<?,?> regions=(Map<?,?>)dimension.getClass().getMethod("getRegions").invoke(dimension);
                int claimed=0,unknown=0;
                for(var value:chunks){
                    JsonObject chunk=value.getAsJsonObject();int cx=chunk.get("x").getAsInt(),cz=chunk.get("z").getAsInt();
                    Object regionPos=xzType.getConstructor(int.class,int.class).newInstance(Math.floorDiv(cx,32),Math.floorDiv(cz,32));
                    Object region=regions.get(regionPos);
                    if(region==null){chunk.addProperty("claimStatus","unknown: map region missing");unknown++;continue;}
                    boolean dataLoaded=(Boolean)region.getClass().getMethod("isDataLoaded").invoke(region);chunk.addProperty("mapDataLoaded",dataLoaded);
                    Object local=xzType.getConstructor(int.class,int.class).newInstance(Math.floorMod(cx,32),Math.floorMod(cz,32));
                    Object record=region.getClass().getMethod("getMapChunk",xzType).invoke(region,local);
                    if(record==null||!dataLoaded){chunk.addProperty("claimStatus","unknown: chunk/map data not loaded");unknown++;continue;}
                    boolean team=((Optional<?>)record.getClass().getMethod("getTeam").invoke(record)).isPresent();
                    boolean date=((Optional<?>)record.getClass().getMethod("getClaimedDate").invoke(record)).isPresent();
                    int version=(Integer)record.getClass().getMethod("getVersion").invoke(record);
                    chunk.addProperty("mapVersion",version);chunk.addProperty("claimStatus",team||date?"claimed":"client reports unclaimed");
                    if(team||date)claimed++;else if(version==0){unknown++;chunk.addProperty("claimStatus","unknown: uninitialised map record");}
                }
                return Jsons.obj("available",true,"checked",unknown==0,"claimed",claimed,"unknown",unknown,
                    "source","FTB Chunks client map already received by the player");
            }
        }catch(ReflectiveOperationException|RuntimeException e){return Jsons.obj("available",false,"checked",false,"reason",e.toString());}
    }
}
