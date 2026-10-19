package dev.atmpilot;

import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.*;
import net.minecraft.world.level.ClipContext;
import com.google.gson.JsonObject;

/** Only ordinary client interactions. No server commands, world mutation, item spawning or teleportation. */
public final class Actions {
    private Actions() {}
    public static Minecraft mc() {
        var mc=Minecraft.getInstance();
        if(mc.player==null||mc.level==null||mc.gameMode==null) throw new IllegalStateException("Not in a world");
        if(!mc.isSameThread()) throw new IllegalStateException("Game operation must run on the client thread");
        return mc;
    }
    public static BlockPos pos(JsonObject data) {
        if(!data.has("x")||!data.has("y")||!data.has("z")) throw new IllegalArgumentException("x, y and z are required");
        int x=Jsons.integer(data,"x",0),y=Jsons.integer(data,"y",0),z=Jsons.integer(data,"z",0);
        if(Math.abs((long)x)>30_000_000||Math.abs((long)z)>30_000_000||Math.abs((long)y)>4096)
            throw new IllegalArgumentException("Block coordinates outside supported bounds");
        return new BlockPos(x,y,z);
    }
    public static void releaseControls() {
        var o=Minecraft.getInstance().options;
        o.keyUp.setDown(false); o.keyDown.setDown(false); o.keyLeft.setDown(false); o.keyRight.setDown(false);
        o.keyJump.setDown(false); o.keyShift.setDown(false); o.keySprint.setDown(false);
        o.keyAttack.setDown(false); o.keyUse.setDown(false);
    }
    public static void look(float yaw,float pitch) {
        if(!Float.isFinite(yaw)||!Float.isFinite(pitch))throw new IllegalArgumentException("Invalid view angles");
        var mc=mc(); mc.player.setYRot(yaw); mc.player.setXRot(Math.max(-90,Math.min(90,pitch)));
    }
    public static void lookAt(Vec3 target) {
        Vec3 delta=target.subtract(mc().player.getEyePosition());
        look((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)),
             (float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z))));
    }
    public static void select(int slot) {
        var mc=mc(); if(slot<0||slot>8) throw new IllegalArgumentException("Hotbar slot must be 0..8");
        mc.player.getInventory().selected=slot;
        mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
    }
    public static void checkReach(BlockPos pos) {
        var mc=mc();
        if(!LoadedWorld.has(pos)) throw new IllegalStateException("Target chunk is not loaded");
        if(mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos))>mc.player.blockInteractionRange()+0.25)
            throw new IllegalStateException("Target is outside ordinary block interaction reach");
    }
    public static void useBlock(BlockPos pos,Direction face) {
        useBlock(pos,face,true);
    }
    public static void useBlock(BlockPos pos,Direction face,boolean aim) {
        var mc=mc(); ServerRules.checkOpen(pos);checkReach(pos);
        Vec3 hit=Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.49));
        var trace=mc.level.clip(new ClipContext(mc.player.getEyePosition(),hit,
            ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
        if(trace.getType()==HitResult.Type.BLOCK&&!trace.getBlockPos().equals(pos))
            throw new IllegalStateException("Block interaction is obstructed by "+trace.getBlockPos());
        if(aim)lookAt(hit);
        var result=mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(hit,face,pos,false));
        if(result.consumesAction()) mc.player.swing(InteractionHand.MAIN_HAND);
    }
    public static void place(BlockPos target) {
        place(target,true);
    }
    public static void place(BlockPos target,boolean aim) {
        var mc=mc();
        ServerRules.checkBuild(target);
        if(!mc.level.getBlockState(target).canBeReplaced()) throw new IllegalStateException("Placement destination is occupied");
        for(Direction face:Direction.values()) {
            BlockPos neighbor=target.relative(face.getOpposite());
            if(LoadedWorld.has(neighbor)&&!mc.level.getBlockState(neighbor).getCollisionShape(mc.level,neighbor).isEmpty()) {
                useBlock(neighbor,face,aim); return;
            }
        }
        throw new IllegalStateException("No reachable placement support");
    }
    public static void click(int container,int slot,int button,ClickType type) {
        var mc=mc(); var menu=mc.player.containerMenu;
        if(menu.containerId!=container) throw new IllegalStateException("Container changed: expected "+container+", actual "+menu.containerId);
        if(slot<0||slot>=menu.slots.size()) throw new IllegalArgumentException("Invalid menu slot; outside clicks are disallowed");
        ServerRules.checkMenuClick(slot);
        if(type==ClickType.CLONE||type==ClickType.THROW||type==ClickType.QUICK_CRAFT)
            throw new IllegalArgumentException("Unsupported click type");
        if(button<0||button>8) throw new IllegalArgumentException("Invalid mouse/hotbar button");
        mc.gameMode.handleInventoryMouseClick(container,slot,button,type,mc.player);
    }
    public static void closeMenu() {
        var mc=mc();
        if(!mc.player.containerMenu.getCarried().isEmpty()) throw new IllegalStateException("Carried stack must be returned before closing");
        mc.player.closeContainer(); mc.setScreen(null);
    }
}
