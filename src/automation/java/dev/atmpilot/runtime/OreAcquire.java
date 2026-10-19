package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Bounded native tunnel/vein mining. Every excavated cell and Ultimine preview is checked. */
public final class OreAcquire implements PilotController.Job,ReflexResumable {
    private final List<BlockPos> targets=new ArrayList<>();
    private final String block;private final PlanModel.Material material;private final int count,budget;
    private long before=-1;private int index,mined,wait,elapsed,preview;
    private PilotController.Job child;private BlockPos breaking,step;private boolean ore;
    private KeyMapping key;
    private List<BlockPos> veinPreview=List.of();
    public OreAcquire(JsonObject data){
        block=Jsons.str(data,"block","");material=new PlanModel.Material(Jsons.str(data,"item",block),"");
        count=Jsons.integer(data,"count",1);budget=Jsons.integer(data,"maxBlocks",256);
        if(block.isBlank()||count<1||count>4096||budget<1||budget>2048)throw new IllegalArgumentException("Bounded ore item/count/block budget required");
        for(var entry:data.getAsJsonArray("targets")){targets.add(Actions.pos(entry.getAsJsonObject()));}
        if(targets.isEmpty()||targets.size()>128)throw new IllegalArgumentException("Provide 1..128 observed ore targets");
    }
    public String description(){return "Acquire "+count+" "+material.id()+"; target "+index+"/"+targets.size()+", excavated "+mined+"/"+budget;}
    private String id(BlockPos p){var mc=Actions.mc();return BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString();}
    private boolean safe(BlockPos p,boolean dig){
        var mc=Actions.mc();if(!LoadedWorld.has(p))return false;
        try{ServerRules.checkBuild(p);}catch(RuntimeException e){return false;}
        var state=mc.level.getBlockState(p);
        if(state.hasBlockEntity()||!state.getFluidState().isEmpty()||state.getDestroySpeed(mc.level,p)<0)return false;
        if(dig&&!state.isAir()&&!id(p).equals(block)&&!Set.of("minecraft:netherrack","minecraft:basalt","minecraft:blackstone","minecraft:soul_soil","minecraft:soul_sand","minecraft:stone","minecraft:deepslate","mysticalagriculture:soulstone","create:scorchia").contains(id(p)))return false;
        for(var d:Direction.values()){BlockPos n=p.relative(d);if(!LoadedWorld.has(n)||!mc.level.getFluidState(n).isEmpty())return false;}
        return true;
    }
    private boolean cell(BlockPos p){
        var mc=Actions.mc();BlockPos floor=p.below();return safe(p,true)&&safe(p.above(),true)&&LoadedWorld.has(floor)
            &&!mc.level.getBlockState(floor).getCollisionShape(mc.level,floor).isEmpty()&&mc.level.getFluidState(floor).isEmpty();
    }
    private boolean dryDropFloor(BlockPos p){
        var mc=Actions.mc();
        for(int dy=1;dy<=16;dy++){
            BlockPos below=p.below(dy);if(!LoadedWorld.has(below)||!mc.level.getFluidState(below).isEmpty())return false;
            if(!mc.level.getBlockState(below).getCollisionShape(mc.level,below).isEmpty())return true;
        }
        return false;
    }
    private BlockPos route(BlockPos target){
        BlockPos start=Actions.mc().player.blockPosition();
        Map<BlockPos,BlockPos> parent=new HashMap<>();Map<BlockPos,Integer> distance=new HashMap<>();
        PriorityQueue<BlockPos> queue=new PriorityQueue<>(Comparator.comparingDouble(p->distance.get(p)+Math.abs(p.getX()-target.getX())+Math.abs(p.getY()-target.getY())+Math.abs(p.getZ()-target.getZ())));
        parent.put(start,start);distance.put(start,0);queue.add(start);BlockPos end=null;
        while(!queue.isEmpty()&&parent.size()<8192){BlockPos p=queue.remove();
            if(p.distSqr(target)<=4&&cell(p)){end=p;break;}
            for(Direction d:Direction.Plane.HORIZONTAL)for(int dy=-1;dy<=1;dy++){
                BlockPos n=p.relative(d).offset(0,dy,0);if(parent.containsKey(n)||n.distSqr(start)>128*128||!cell(n))continue;
                parent.put(n,p);distance.put(n,distance.get(p)+1);queue.add(n);
            }
        }
        if(end==null)throw new IllegalStateException("No bounded dry native tunnel route to "+target);
        if(end.equals(start))return start;
        while(!parent.get(end).equals(start))end=parent.get(end);return end;
    }
    private void release(){if(key!=null)key.setDown(false);preview=0;veinPreview=List.of();}
    private void beginMine(BlockPos p,boolean vein)throws Exception{
        if(!safe(p,true))throw new IllegalStateException("Excavation cell no longer approved/dry: "+p);
        if(vein&&!dryDropFloor(p))throw new IllegalStateException("Ore drops have no observed dry catch floor; prepare a catch platform or choose another vein");
        if(mined>=budget)throw new IllegalStateException("Excavation budget exhausted; checkpoint retained");
        breaking=p;ore=vein;
        if(vein){
            try{key=(KeyMapping)Class.forName("dev.ftb.mods.ftbultimine.client.FTBUltimineClient").getField("keyBindUltimine").get(null);key.setDown(true);preview=1;Actions.lookAt(Vec3.atCenterOf(p));return;}
            catch(ReflectiveOperationException unavailable){release();}
        }
        child=PilotJobs.create(Jsons.obj("type","mine","x",p.getX(),"y",p.getY(),"z",p.getZ()));
    }
    public boolean tick(PilotController c)throws Exception{
        var mc=Actions.mc();if(before<0){before=WorldView.count(material);Actions.closeMenu();}
        if(++elapsed>24000)throw new IllegalStateException("Ore acquisition timed out; inspect actual inventory before resuming");
        if(wait-->0)return false;
        if(child!=null){
            if(key!=null&&key.isDown()){
                Class<?> type=Class.forName("dev.ftb.mods.ftbultimine.client.FTBUltimineClient");Object instance=type.getMethod("getInstance").invoke(null);
                Collection<?> selected=(Collection<?>)type.getMethod("getSelectedBlocks").invoke(instance);
                for(Object value:selected)if(!(value instanceof BlockPos p)||!veinPreview.contains(p)||!safe(p,true)||!id(p).equals(block)){release();break;}
            }
            if(child.tick(c)){child=null;release();if(breaking!=null){mined++;wait=10;}else step=null;}return false;
        }
        if(breaking!=null&&preview==0){
            if(!mc.level.getBlockState(breaking).isAir())throw new IllegalStateException("Server rejected excavation; no automatic replay: "+breaking);
            BlockPos drop=breaking;breaking=null;
            if(ore&&cell(drop)&&mc.level.getBlockState(drop.above()).isAir()){
                child=PilotJobs.create(Jsons.obj("type","walk","x",drop.getX(),"y",drop.getY(),"z",drop.getZ()));wait=10;return false;
            }
        }
        if(WorldView.count(material)-before>=count){release();Actions.releaseControls();return true;}
        if(preview==0&&breaking==null&&mc.player.getAbilities().flying){
            net.minecraft.world.entity.item.ItemEntity nearest=null;double distance=256;
            for(var entity:mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,mc.player.getBoundingBox().inflate(16))){
                if(!WorldView.material(entity.getItem()).equals(material)||entity.isInLava())continue;
                double d=mc.player.distanceToSqr(entity);if(d<distance){nearest=entity;distance=d;}
            }
            if(nearest!=null){BlockPos p=nearest.blockPosition();
                if(LoadedWorld.has(p)&&mc.level.getBlockState(p).isAir()&&LoadedWorld.has(p.above())&&mc.level.getBlockState(p.above()).isAir()){
                    child=new Flight.Fly(Jsons.obj("x",p.getX(),"y",p.getY(),"z",p.getZ(),"cruiseY",(int)Math.ceil(mc.player.getY())+3,"hover",true));return false;
                }
            }
        }
        if(preview>0){
            Actions.lookAt(Vec3.atCenterOf(breaking));if(preview++<6)return false;
            boolean approved=false;
            try{
                Class<?> type=Class.forName("dev.ftb.mods.ftbultimine.client.FTBUltimineClient");Object instance=type.getMethod("getInstance").invoke(null);
                Collection<?> selected=(Collection<?>)type.getMethod("getSelectedBlocks").invoke(instance);
                approved=!selected.isEmpty()&&selected.size()<=budget-mined;
                for(Object value:selected)if(!(value instanceof BlockPos p)||!safe(p,true)||!id(p).equals(block)||!dryDropFloor(p)){approved=false;break;}
                if(approved){veinPreview=new ArrayList<>();for(Object value:selected)veinPreview.add(((BlockPos)value).immutable());mined+=selected.size()-1;}
            }catch(ReflectiveOperationException unavailable){approved=false;}
            if(!approved)release();else preview=0;
            child=PilotJobs.create(Jsons.obj("type","mine","x",breaking.getX(),"y",breaking.getY(),"z",breaking.getZ()));return false;
        }
        while(index<targets.size()&&!id(targets.get(index)).equals(block))index++;
        if(index>=targets.size())throw new IllegalStateException("Observed ore targets exhausted; actual gain "+(WorldView.count(material)-before)+"; replan without replay");
        BlockPos target=targets.get(index);if(!safe(target,true))throw new IllegalStateException("Ore target outside approved dry region");
        if(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))<20){
            var hit=mc.level.clip(new net.minecraft.world.level.ClipContext(mc.player.getEyePosition(),Vec3.atCenterOf(target),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.ANY,mc.player));
            if(hit.getBlockPos().equals(target)){beginMine(target,true);return false;}
        }
        if(step==null)step=route(target);
        if(step.equals(mc.player.blockPosition()))throw new IllegalStateException("Ore approach obstructed; retain local checkpoint");
        // A descending step also needs clearance at the current head height before
        // the player can cross its edge and fall to the lower destination floor.
        if(step.getY()<mc.player.blockPosition().getY()&&!mc.level.getBlockState(step.above(2)).isAir()){beginMine(step.above(2),false);return false;}
        if(!mc.level.getBlockState(step.above()).isAir()){beginMine(step.above(),false);return false;}
        if(!mc.level.getBlockState(step).isAir()){beginMine(step,false);return false;}
        child=PilotJobs.create(Jsons.obj("type","walk","x",step.getX(),"y",step.getY(),"z",step.getZ()));c.note(description());return false;
    }
    public boolean resumeAfterLocalCombat(){if(child!=null)child.cancel();child=null;release();breaking=null;step=null;wait=10;return true;}
    public void cancel(){if(child!=null)child.cancel();release();Actions.releaseControls();}
}
