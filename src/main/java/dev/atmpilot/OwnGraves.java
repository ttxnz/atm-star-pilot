package dev.atmpilot;

import net.minecraft.core.BlockPos;
import java.util.UUID;

/** Ownership comes from the ordinary client-synchronized Tombstone block entity. */
public final class OwnGraves {
    private OwnGraves(){}
    public static boolean isOwned(BlockPos position){
        var mc=Actions.mc();if(!LoadedWorld.has(position))return false;
        var entity=mc.level.getBlockEntity(position);
        if(entity==null||!entity.getClass().getName().equals("ovh.corail.tombstone.block.entity.BlockEntityPlayerGrave"))return false;
        try{return mc.player.getUUID().equals((UUID)entity.getClass().getMethod("getOwnerId").invoke(entity));}
        catch(ReflectiveOperationException e){return false;}
    }
}
