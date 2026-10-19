package dev.atmpilot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** ClientLevel.hasChunkAt may report true for a placeholder. Never treat that placeholder as observed air. */
public final class LoadedWorld {
    private LoadedWorld(){}
    public static LevelChunk chunk(int x,int z){return Actions.mc().level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);}
    public static boolean has(BlockPos position){return chunk(Math.floorDiv(position.getX(),16),Math.floorDiv(position.getZ(),16))!=null;}
}
