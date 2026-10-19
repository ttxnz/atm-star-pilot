package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Terrain reconnaissance using only already received chunks; never requests or changes chunks. */
public final class SurfaceScan implements PilotController.Job {
    private final JsonObject data;public SurfaceScan(JsonObject data){this.data=data.deepCopy();}
    private static final Set<String> NATURAL=Set.of("minecraft:grass_block","minecraft:dirt","minecraft:coarse_dirt","minecraft:rooted_dirt","minecraft:podzol","minecraft:mycelium","minecraft:stone","minecraft:sand","minecraft:red_sand","minecraft:snow_block","minecraft:packed_mud");
    public String description(){return "Read loaded surface heights and dry natural landing candidates";}
    public boolean tick(PilotController c)throws Exception {
        var mc=Actions.mc();int radius=Jsons.integer(data,"radius",16);if(radius<0||radius>31)throw new IllegalArgumentException("Surface radius must be 0..31");
        int centerX=Jsons.integer(data,"x",mc.player.blockPosition().getX()),centerZ=Jsons.integer(data,"z",mc.player.blockPosition().getZ());
        int minX=Jsons.integer(data,"minX",centerX-radius),maxX=Jsons.integer(data,"maxX",centerX+radius),minZ=Jsons.integer(data,"minZ",centerZ-radius),maxZ=Jsons.integer(data,"maxZ",centerZ+radius);
        long columns=((long)maxX-minX+1)*((long)maxZ-minZ+1);if(minX>maxX||minZ>maxZ||columns<1||columns>4096||Math.abs((long)minX)>30000000||Math.abs((long)maxX)>30000000||Math.abs((long)minZ)>30000000||Math.abs((long)maxZ)>30000000)throw new IllegalArgumentException("Surface bounds must contain 1..4096 supported columns");
        int unloaded=0,maxSurface=mc.level.getMinBuildHeight();List<JsonObject> candidates=new ArrayList<>();JsonArray structures=new JsonArray();Set<Long> seenChunks=new HashSet<>();
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){
            int cx=Math.floorDiv(x,16),cz=Math.floorDiv(z,16);var chunk=LoadedWorld.chunk(cx,cz);if(chunk==null){unloaded++;continue;}
            if(seenChunks.add(ChunkPos.asLong(cx,cz)))for(var entity:chunk.getBlockEntities().values()){
                BlockPos p=entity.getBlockPos();if(p.getX()>=minX&&p.getX()<=maxX&&p.getZ()>=minZ&&p.getZ()<=maxZ)structures.add(Jsons.obj("x",p.getX(),"y",p.getY(),"z",p.getZ(),"block",BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString()));}
            maxSurface=Math.max(maxSurface,chunk.getHeight(Heightmap.Types.WORLD_SURFACE,Math.floorMod(x,16),Math.floorMod(z,16))+1);
            // WORLD_SURFACE is synchronized to the client; the no-leaves map may be unprimed here.
            int top=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,Math.floorMod(x,16),Math.floorMod(z,16));
            for(int y=Math.min(mc.level.getMaxBuildHeight()-2,top+2);y>=Math.max(mc.level.getMinBuildHeight()+1,top-2);y--){
                BlockPos p=new BlockPos(x,y,z);if(!LoadedWorld.has(p)||!LoadedWorld.has(p.above())||!LoadedWorld.has(p.below()))continue;var floor=mc.level.getBlockState(p.below());String id=BuiltInRegistries.BLOCK.getKey(floor.getBlock()).toString();
                if(!NATURAL.contains(id)||!floor.isFaceSturdy(mc.level,p.below(),Direction.UP)||!mc.level.getBlockState(p).getCollisionShape(mc.level,p).isEmpty()||!mc.level.getBlockState(p.above()).getCollisionShape(mc.level,p.above()).isEmpty()||!mc.level.getFluidState(p).isEmpty()||!mc.level.getFluidState(p.above()).isEmpty())continue;
                boolean clear=true;for(int probe=y;probe<=top+1;probe++){BlockPos above=new BlockPos(x,probe,z);if(!mc.level.getBlockState(above).getCollisionShape(mc.level,above).isEmpty()||!mc.level.getFluidState(above).isEmpty()){clear=false;break;}}if(!clear)continue;
                candidates.add(Jsons.obj("x",x,"y",y,"z",z,"floor",id,"biome",mc.level.getBiome(p).unwrapKey().map(k->k.location().toString()).orElse("unregistered")));break;
            }
        }
        candidates.sort(Comparator.comparingDouble(p->Math.pow(p.get("x").getAsInt()-centerX,2)+Math.pow(p.get("z").getAsInt()-centerZ,2)));
        JsonObject report=Jsons.obj("observedAt",Instant.now().toString(),"dimension",mc.level.dimension().location().toString(),"bounds",Jsons.obj("minX",minX,"maxX",maxX,"minZ",minZ,"maxZ",maxZ),"columns",columns,"unloadedColumns",unloaded,"maxSurfaceY",maxSurface,"candidateCount",candidates.size(),"candidates",candidates.stream().limit(128).toList(),"blockEntities",structures,
            "note","Natural landing candidates do not grant building permission. Survey ownership, structures and separation before adding a build area. Only cached received chunks were read.");
        Files.writeString(c.directory.resolve("last-surface-scan.json"),Jsons.GSON.toJson(report),StandardCharsets.UTF_8);return true;
    }
}
