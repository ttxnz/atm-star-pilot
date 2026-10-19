package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Ordinary Ctrl-Q stack drops, native AE water conversion and ordinary proximity pickup only. */
public final class FluixBatch implements PilotController.Job {
    private static final String RECIPE="ae2:transform|ae2:transform/fluix_crystals";
    private static final PlanModel.Material OUTPUT=new PlanModel.Material("ae2:fluix_crystal","");
    private final BlockPos water,stand,retreat;private final int count;
    private final List<PlanModel.Material> inputs=new ArrayList<>();private final List<Long> before=new ArrayList<>();
    private long baseline,target;private int phase,index,sub,wait,elapsed,alignTicks,worldWait;private PilotController.Job child;
    public FluixBatch(JsonObject data){water=Actions.pos(data);stand=water.north().above();retreat=water.north(3);count=Jsons.integer(data,"count",1);
        if(count<1||count>64)throw new IllegalArgumentException("Fluix batch requires 1..64 exact input sets");}
    public String description(){return "Native water fluix batch "+count+" sets (phase "+phase+", drops "+index+"/3)";}
    private List<ItemEntity> entities(){return Actions.mc().level.getEntitiesOfClass(ItemEntity.class,new AABB(water).inflate(2));}
    private long visible(PlanModel.Material material){long result=0;for(var e:entities())if(WorldView.material(e.getItem()).equals(material))result+=e.getItem().getCount();return result;}
    private void report(PilotController c,String state)throws Exception {JsonArray items=new JsonArray();for(var e:entities())items.add(Jsons.obj("entityId",e.getId(),"x",e.getX(),"y",e.getY(),"z",e.getZ(),"inWater",e.isInWater(),"item",WorldView.stack(e.getItem())));
        Files.writeString(c.directory.resolve("last-fluix-batch.json"),Jsons.GSON.toJson(Jsons.obj("observedAt",Instant.now().toString(),"state",state,"recipe",RECIPE,
            "inputSets",count,"expectedOutput",count*2,"phase",phase,"dropsConfirmed",index,"inventoryBefore",baseline,"targetInventory",target,
            "inventoryNow",WorldView.count(OUTPUT),"worldItems",items,"x",water.getX(),"y",water.getY(),"z",water.getZ())),StandardCharsets.UTF_8);}
    private void guard(){var mc=Actions.mc();for(BlockPos p:List.of(water,water.above(),stand,retreat)){ServerRules.checkBuild(p);if(!LoadedWorld.has(p))throw new IllegalStateException("Own conversion fixture not loaded");}
        if(!mc.level.getFluidState(water).is(Fluids.WATER)||!mc.level.getFluidState(water).isSource()||!mc.level.getBlockState(water.above()).isAir())throw new IllegalStateException("This adapter needs an open contained water cell");
        for(Direction side:Direction.Plane.HORIZONTAL){var wall=water.relative(side);ServerRules.checkBuild(wall);if(!mc.level.getBlockState(wall).isFaceSturdy(mc.level,wall,side.getOpposite()))throw new IllegalStateException("Conversion wall changed");}}
    private boolean walk(PilotController c,BlockPos destination)throws Exception {if(child==null)child=PilotJobs.create(Jsons.obj("type","walk","x",destination.getX(),"y",destination.getY(),"z",destination.getZ()));
        if(!child.tick(c))return false;child=null;Actions.releaseControls();return true;}
    private boolean align(){var mc=Actions.mc();if(++alignTicks>60||Math.abs(mc.player.getY()-(water.getY()+1))>0.2)throw new IllegalStateException("Safe wall-top drop/pickup stance not reached");
        var delta=new Vec3(water.getX()+0.5,water.getY()+1,water.getZ()-0.4).subtract(mc.player.position());
        if(delta.horizontalDistanceSqr()<0.0225){Actions.releaseControls();alignTicks=0;return true;}
        Actions.look((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)),15);mc.options.keyUp.setDown(true);mc.options.keySprint.setDown(false);mc.options.keyJump.setDown(false);return false;}
    private void select(PlanModel.Material material){var mc=Actions.mc();for(int i=0;i<36;i++)if(WorldView.material(mc.player.getInventory().getItem(i)).equals(material)&&mc.player.getInventory().getItem(i).getCount()==count){
        if(i>=9){Actions.click(mc.player.inventoryMenu.containerId,i,8,ClickType.SWAP);i=8;}Actions.select(i);return;}
        throw new IllegalStateException("Prepare one exact "+count+"-item stack before dropping "+material.id());}
    public boolean tick(PilotController c)throws Exception {
        var mc=Actions.mc();guard();if(++elapsed>2400){report(c,"timed_out_needs_replan");throw new IllegalStateException("Fluix drop batch timed out; do not replay drops");}if(wait-->0)return false;
        if(phase==0){Actions.closeMenu();if(!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Clear and account for the cursor first");var recipe=JeiBridge.entry(RECIPE).recipe();
            if(!recipe.type().equals("ae2:transform")||!recipe.deterministic()||recipe.inputs().size()!=3||recipe.outputs().size()!=1||!recipe.outputs().getFirst().material().equals(OUTPUT)||recipe.outputs().getFirst().count()!=2)throw new IllegalStateException("Live fluix recipe changed");
            Set<String> expected=Set.of("ae2:charged_certus_quartz_crystal","minecraft:redstone","minecraft:quartz");
            for(var ingredient:recipe.inputs()){if(ingredient.alternatives().size()!=1||ingredient.alternatives().getFirst().count()!=1||!expected.contains(ingredient.alternatives().getFirst().material().id()))throw new IllegalStateException("Unexpected fluix recipe input");
                var material=ingredient.alternatives().getFirst().material();boolean exact=false;for(int i=0;i<36;i++)if(WorldView.material(mc.player.getInventory().getItem(i)).equals(material)&&mc.player.getInventory().getItem(i).getCount()==count)exact=true;
                if(!exact||visible(material)>0)throw new IllegalStateException("Need three exact player stacks and no unfinished world inputs");inputs.add(material);before.add(WorldView.count(material));}
            if(new HashSet<>(inputs).size()!=3||visible(OUTPUT)>0)throw new IllegalStateException("Unfinished conversion output is present");baseline=WorldView.count(OUTPUT);target=baseline+count*2L;phase=1;report(c,"initialized");
        }
        if(phase==1){if(!walk(c,stand))return false;phase=2;}
        if(phase==2){if(!align())return false;phase=3;wait=4;return false;}
        if(phase==3){
            if(index==3){Actions.look(0,35);child=PilotJobs.create(Jsons.obj("type","move","back",true,"ticks",10));phase=4;return false;}
            if(sub==0){select(inputs.get(index));Actions.lookAt(Vec3.atCenterOf(water));sub=1;wait=2;return false;}
            if(sub==1){if(!WorldView.material(mc.player.getMainHandItem()).equals(inputs.get(index))||mc.player.getMainHandItem().getCount()!=count)throw new IllegalStateException("Prepared drop stack changed");
                report(c,"drop_intent");mc.player.drop(true);sub=2;wait=3;return false;}
            if(WorldView.count(inputs.get(index))!=before.get(index)-count)throw new IllegalStateException("Normal stack drop not confirmed; no drop was repeated");index++;sub=0;report(c,"drop_confirmed");return false;
        }
        if(phase==4){if(!child.tick(c))return false;child=null;Actions.releaseControls();phase=5;wait=10;return false;}
        if(phase==5){if(!walk(c,retreat))return false;phase=6;report(c,"waiting_native_transformation");}
        if(phase==6){
            if(WorldView.count(OUTPUT)-baseline+visible(OUTPUT)!=count*2L||inputs.stream().anyMatch(m->visible(m)>0)){
                if(++worldWait>30){report(c,"world_transformation_not_confirmed");throw new IllegalStateException("Native water output not confirmed; inspect the retained world items instead of replaying");}wait=10;return false;}
            for(int i=0;i<3;i++)if(WorldView.count(inputs.get(i))!=before.get(i)-count)throw new IllegalStateException("An ingredient was picked up before conversion");
            report(c,"world_output_verified");if(WorldView.count(OUTPUT)==target){phase=9;}else phase=7;
        }
        if(phase==7){if(!walk(c,stand))return false;phase=8;}
        if(phase==8){if(!align())return false;phase=9;wait=20;return false;}
        if(phase==9){if(WorldView.count(OUTPUT)!=target){if(++worldWait>50)throw new IllegalStateException("Ordinary output pickup not confirmed; world output retained");wait=10;return false;}
            if(visible(OUTPUT)>0||inputs.stream().anyMatch(m->visible(m)>0))throw new IllegalStateException("Unexpected conversion items remain");
            for(int i=0;i<3;i++)if(WorldView.count(inputs.get(i))!=before.get(i)-count)throw new IllegalStateException("Final ingredient quantity differs from the batch");report(c,"completed_verified");return true;}
        return false;
    }
    public void cancel(){if(child!=null)child.cancel();Actions.releaseControls();try{report(PilotMod.CONTROLLER,"interrupted_needs_replan");}catch(Exception ignored){}}
}
