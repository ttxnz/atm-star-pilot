package dev.atmpilot.vanilla;

import dev.atmpilot.Actions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.util.*;
import java.util.function.Predicate;

final class VanillaWorld {
    private VanillaWorld() {}
    static Minecraft mc(){return Actions.mc();}
    static String id(ItemStack s){return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();}
    static String id(BlockState s){return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();}
    static int count(String id){int n=0;for(int i=0;i<36;i++){var s=mc().player.getInventory().getItem(i);if(id(s).equals(id))n+=s.getCount();}return n;}
    static int count(Predicate<ItemStack> p){int n=0;for(int i=0;i<36;i++){var s=mc().player.getInventory().getItem(i);if(p.test(s))n+=s.getCount();}return n;}
    static int slot(Predicate<ItemStack> p){for(int i=0;i<36;i++)if(p.test(mc().player.getInventory().getItem(i)))return i;return -1;}
    static boolean equip(Predicate<ItemStack> p){
        var m=mc();int s=slot(p);if(s<0)return false;
        if(s<9){Actions.select(s);return p.test(m.player.getMainHandItem());}
        if(m.player.containerMenu.containerId!=0||!m.player.containerMenu.getCarried().isEmpty())return false;
        m.gameMode.handleInventoryMouseClick(0,s,8,ClickType.SWAP,m.player);Actions.select(8);return false;
    }
    static boolean equip(String id){return equip(s->id(s).equals(id));}
    static boolean emptyHand(){return equip(ItemStack::isEmpty);}
    static boolean loaded(BlockPos p){return mc().level.hasChunkAt(p)&&mc().level.isInWorldBounds(p);}
    static boolean dangerous(BlockPos p){if(!loaded(p))return true;var s=mc().level.getBlockState(p);var id=id(s);return !s.getFluidState().isEmpty()||id.contains("fire")||id.contains("cactus")||id.contains("magma")||id.contains("berry_bush")||id.contains("powder_snow");}
    static boolean clear(BlockPos p){return loaded(p)&&!dangerous(p)&&mc().level.getBlockState(p).getCollisionShape(mc().level,p).isEmpty();}
    static boolean floor(BlockPos p){return loaded(p)&&!dangerous(p)&&mc().level.getBlockState(p).isCollisionShapeFullBlock(mc().level,p);}
    static boolean mineable(BlockPos p){
        if(!loaded(p)||dangerous(p))return false;var s=mc().level.getBlockState(p);
        if(clear(p))return true;if(s.hasBlockEntity()||s.getDestroySpeed(mc().level,p)<0||s.getDestroySpeed(mc().level,p)>50)return false;
        return !s.requiresCorrectToolForDrops()||slot(i->i.isCorrectToolForDrops(s))>=0;
    }
    static BlockHitResult hit(BlockPos p){
        Vec3 eye=mc().player.getEyePosition();Vec3[] points={Vec3.atCenterOf(p),new Vec3(p.getX()+.5,p.getY()+.95,p.getZ()+.5),new Vec3(p.getX()+.1,p.getY()+.5,p.getZ()+.1)};
        for(var to:points){if(eye.distanceTo(to)>mc().player.blockInteractionRange())continue;
            var h=mc().level.clip(new ClipContext(eye,to,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc().player));
            if(h.getType()==HitResult.Type.BLOCK&&h.getBlockPos().equals(p))return h;}
        return null;
    }
    static boolean visible(BlockPos p){
        var eye=mc().player.getEyePosition();var fluid=mc().level.getBlockState(p).getFluidState().isEmpty()?ClipContext.Fluid.NONE:ClipContext.Fluid.SOURCE_ONLY;
        var h=mc().level.clip(new ClipContext(eye,Vec3.atCenterOf(p),ClipContext.Block.OUTLINE,fluid,mc().player));
        return h.getType()==HitResult.Type.BLOCK&&h.getBlockPos().equals(p);
    }
    /** Visible block faces only: no seed lookup, server chunk inspection or through-wall ore scan. */
    static BlockPos find(Predicate<BlockState> accept,int radius){
        var m=mc();var c=m.player.blockPosition();BlockPos best=null;double distance=Double.MAX_VALUE;int budget=45000;
        for(int r=0;r<=Math.min(radius,24);r++)for(int x=-r;x<=r;x++)for(int z=-r;z<=r;z++){
            if(Math.max(Math.abs(x),Math.abs(z))!=r)continue;
            for(int y=-Math.min(r+2,16);y<=Math.min(r+7,20);y++){
                if(--budget<0)return best;var p=c.offset(x,y,z);if(!loaded(p))continue;var s=m.level.getBlockState(p);
                if(accept.test(s)&&p.distSqr(c)<distance&&visible(p)){best=p.immutable();distance=p.distSqr(c);}
            }
        }return best;
    }
    static List<Entity> entities(double radius,Predicate<Entity> accept){return mc().level.getEntities(mc().player,mc().player.getBoundingBox().inflate(radius),accept).stream().sorted(Comparator.comparingDouble(mc().player::distanceTo)).toList();}
    static boolean interact(BlockPos p){var h=hit(p);if(h==null)return false;Actions.lookAt(h.getLocation());var result=mc().gameMode.useItemOn(mc().player,InteractionHand.MAIN_HAND,h);if(!result.consumesAction())mc().gameMode.useItem(mc().player,InteractionHand.MAIN_HAND);mc().player.swing(InteractionHand.MAIN_HAND);return true;}
    static int blocks(){return count(s->s.getItem() instanceof BlockItem&&!id(s).contains("ore")&&!id(s).contains("obsidian")&&!id(s).contains("crafting")&&!id(s).contains("furnace")&&!id(s).contains("log"));}
    static boolean equipBuildingBlock(){return equip(s->s.getItem() instanceof BlockItem&&Set.of("minecraft:cobblestone","minecraft:cobbled_deepslate","minecraft:dirt","minecraft:netherrack","minecraft:oak_planks").contains(id(s)));}
    static boolean place(BlockPos p){
        var m=mc();if(!loaded(p)||!m.level.getBlockState(p).canBeReplaced()||dangerous(p))return false;
        for(Direction face:Direction.values()){
            var support=p.relative(face.getOpposite());if(!floor(support))continue;
            Vec3 to=Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.5));
            if(m.player.getEyePosition().distanceTo(to)>m.player.blockInteractionRange())continue;
            var ray=m.level.clip(new ClipContext(m.player.getEyePosition(),to,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,m.player));
            if(ray.getType()==HitResult.Type.BLOCK&&!ray.getBlockPos().equals(support))continue;
            Actions.lookAt(to);m.gameMode.useItemOn(m.player,InteractionHand.MAIN_HAND,new BlockHitResult(to,face,support,false));m.player.swing(InteractionHand.MAIN_HAND);return true;
        }return false;
    }
    static boolean isLog(ItemStack s){return s.is(net.minecraft.tags.ItemTags.LOGS);}
    static boolean isFood(ItemStack s){var f=s.get(DataComponents.FOOD);return f!=null&&f.nutrition()>0&&!Set.of("minecraft:rotten_flesh","minecraft:spider_eye","minecraft:poisonous_potato","minecraft:pufferfish","minecraft:chicken").contains(id(s));}
    static void close(){if(mc().screen!=null||mc().player.containerMenu.containerId!=0)Actions.closeMenu();}
}
