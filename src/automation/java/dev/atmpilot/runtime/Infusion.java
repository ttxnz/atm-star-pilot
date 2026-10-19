package dev.atmpilot.runtime;

import com.google.gson.*;
import dev.atmpilot.*;
import net.minecraft.core.*;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.items.IItemHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Visible altar/pedestal items, normal right-click insertion, a real lever and normal output pickup only. */
public final class Infusion {
    private static Object altar(BlockPos pos)throws Exception{
        ServerRules.checkBuild(pos);if(!LoadedWorld.has(pos))throw new IllegalStateException("Altar chunk not received");
        Object tile=Actions.mc().level.getBlockEntity(pos);
        if(tile==null||!tile.getClass().getName().equals("com.blakebr0.mysticalagriculture.tileentity.InfusionAltarTileEntity"))throw new IllegalStateException("Own infusion altar not observed at "+pos);
        return tile;
    }
    private static IItemHandler display(BlockPos pos)throws Exception{
        ServerRules.checkBuild(pos);if(!LoadedWorld.has(pos))throw new IllegalStateException("Pedestal chunk not received");
        Object tile=Actions.mc().level.getBlockEntity(pos);
        if(tile==null||!Set.of("com.blakebr0.mysticalagriculture.tileentity.InfusionAltarTileEntity","com.blakebr0.mysticalagriculture.tileentity.InfusionPedestalTileEntity").contains(tile.getClass().getName()))throw new IllegalStateException("Expected the visible altar/pedestal display");
        return (IItemHandler)tile.getClass().getMethod("getInventory").invoke(tile);
    }
    private static List<BlockPos> positions(BlockPos pos)throws Exception{
        Object tile=altar(pos);List<?> list=(List<?>)tile.getClass().getMethod("getPedestalPositions").invoke(tile);List<BlockPos> result=new ArrayList<>();
        if(list.size()!=8)throw new IllegalStateException("Native altar requires an unexpected pedestal count");
        for(Object value:list){var pedestal=(BlockPos)value;ServerRules.checkBuild(pedestal);
            if(pedestal.getY()!=pos.getY()||pedestal.distSqr(pos)>9)throw new IllegalStateException("Native pedestal location is outside the observed layout");result.add(pedestal);
        }return result;
    }
    private static void save(String file,JsonObject value)throws Exception{Files.writeString(PilotMod.CONTROLLER.directory.resolve(file),Jsons.GSON.toJson(value),StandardCharsets.UTF_8);}
    public static final class Inspect implements PilotController.Job {
        private final BlockPos pos;public Inspect(JsonObject data){pos=Actions.pos(data);}
        public String description(){return "Read own visible Mystical Agriculture altar displays";}
        public boolean tick(PilotController controller)throws Exception{
            JsonArray pedestals=new JsonArray();for(BlockPos p:positions(pos))pedestals.add(Jsons.obj("x",p.getX(),"y",p.getY(),"z",p.getZ(),"item",WorldView.stack(display(p).getStackInSlot(0))));
            Object tile=altar(pos);save("last-infusion-inspection.json",Jsons.obj("observedAt",Instant.now().toString(),"dimension",Actions.mc().level.dimension().location().toString(),
                "x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),"active",tile.getClass().getMethod("isActive").invoke(tile),
                "input",WorldView.stack(display(pos).getStackInSlot(0)),"output",WorldView.stack(display(pos).getStackInSlot(1)),"pedestals",pedestals));return true;
        }
    }
    public static final class Run implements PilotController.Job {
        private final BlockPos pos,lever;private final String recipeId;private final List<BlockPos> targets=new ArrayList<>();private final List<PlanModel.Material> materials=new ArrayList<>();
        private PlanModel.Material product;private long target,beforeInsert;private int phase,index,sub,wait,elapsed;private boolean initialized,activated,retrieved;private PilotController.Job approach;
        public Run(JsonObject data){pos=Actions.pos(data);lever=Actions.pos(data.getAsJsonObject("lever"));recipeId=Jsons.str(data,"recipe","");}
        public String description(){return "Normal infusion via "+recipeId+" (phase "+phase+", display "+index+"/9)";}
        private boolean powered(){var state=Actions.mc().level.getBlockState(lever);if(!state.is(Blocks.LEVER))throw new IllegalStateException("The recorded activation lever is absent");return state.getValue(LeverBlock.POWERED);}
        private boolean near(BlockPos p,PilotController controller)throws Exception{
            if(approach==null)approach=PilotJobs.create(Jsons.obj("type","walk_near","x",p.getX(),"y",p.getY(),"z",p.getZ()));
            if(!approach.tick(controller))return false;approach=null;Actions.releaseControls();return true;
        }
        private void emptyHand(){for(int i=0;i<9;i++)if(Actions.mc().player.getInventory().getItem(i).isEmpty()){Actions.select(i);return;}throw new IllegalStateException("An empty hotbar slot is required for altar/lever pickup");}
        private void select(PlanModel.Material material){
            var mc=Actions.mc();for(int i=0;i<36;i++)if(!mc.player.getInventory().getItem(i).isEmpty()&&WorldView.material(mc.player.getInventory().getItem(i)).equals(material)){
                if(i>=9){Actions.click(mc.player.inventoryMenu.containerId,i,8,ClickType.SWAP);Actions.select(8);}else Actions.select(i);return;
            }throw new IllegalStateException("Missing staged infusion ingredient: "+material.id());
        }
        private void report(String state)throws Exception{save("last-infusion-job.json",Jsons.obj("observedAt",Instant.now().toString(),"recipe",recipeId,"state",state,
            "dimension",Actions.mc().level.dimension().location().toString(),"x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),"phase",phase,"displayIndex",index,
            "materials",materials,"product",product,"targetInventory",target,"inventoryNow",product==null?0:WorldView.count(product),"activationSent",activated));}
        private void initialize()throws Exception{
            Actions.closeMenu();ServerRules.checkBuild(lever);if(lever.distManhattan(pos)!=1)throw new IllegalArgumentException("The native lever must be directly adjacent to the altar");powered();
            var recipe=JeiBridge.entry(recipeId).recipe();if(!recipe.type().equals("mysticalagriculture:infusion")||!recipe.deterministic()||recipe.inputs().size()!=9||recipe.outputs().size()!=1||recipe.outputs().getFirst().count()!=1)
                throw new IllegalArgumentException("This adapter requires one deterministic nine-display infusion recipe producing one item");
            product=recipe.outputs().getFirst().material();target=WorldView.count(product)+1;targets.add(pos);targets.addAll(positions(pos));
            ItemStack output=display(pos).getStackInSlot(1);
            if(!output.isEmpty()){if(!WorldView.material(output).equals(product)||output.getCount()!=1)throw new IllegalStateException("Another infusion output is awaiting pickup");phase=4;initialized=true;report("existing_output_needs_pickup");return;}
            Map<PlanModel.Material,Long> available=new HashMap<>(WorldView.inventory());
            for(int i=0;i<9;i++){
                var ingredient=recipe.inputs().get(i);ItemStack current=display(targets.get(i)).getStackInSlot(0);PlanModel.Amount choice;
                if(!current.isEmpty())choice=ingredient.alternatives().stream().filter(a->a.count()==1&&current.getCount()==1&&a.material().equals(WorldView.material(current))).findFirst().orElseThrow(()->new IllegalStateException("A display contains another recipe's ingredient"));
                else {choice=ingredient.alternatives().stream().filter(a->a.count()==1&&available.getOrDefault(a.material(),0L)>0).findFirst().orElseThrow(()->new IllegalStateException("The full infusion recipe must be present before staging"));available.merge(choice.material(),-1L,Long::sum);}
                materials.add(choice.material());
            }
            phase=powered()?9:1;initialized=true;report("initialized");
        }
        public boolean tick(PilotController controller)throws Exception{
            var mc=Actions.mc();ServerRules.checkBuild(pos);ServerRules.checkBuild(lever);
            if(!initialized)initialize();if(++elapsed>2400){report("timed_out_needs_replan");throw new IllegalStateException("Infusion timed out; visible inputs and checkpoint retained");}
            if(wait-->0)return false;
            if(phase==9){if(!near(lever,controller))return false;emptyHand();Actions.useBlock(lever,Direction.UP);phase=1;wait=10;return false;}
            if(phase==1){
                if(index>=9){phase=2;return false;}BlockPos destination=targets.get(index);ItemStack current=display(destination).getStackInSlot(0);
                if(sub==0){
                    if(!current.isEmpty()){if(current.getCount()!=1||!WorldView.material(current).equals(materials.get(index)))throw new IllegalStateException("Infusion display changed");index++;return false;}
                    if(!near(destination,controller))return false;select(materials.get(index));beforeInsert=WorldView.count(materials.get(index));sub=1;wait=4;return false;
                }
                if(sub==1){if(!current.isEmpty()||!WorldView.material(mc.player.getMainHandItem()).equals(materials.get(index)))throw new IllegalStateException("Infusion input changed before the normal click");Actions.useBlock(destination,Direction.UP);sub=2;wait=8;return false;}
                if(current.getCount()!=1||!WorldView.material(current).equals(materials.get(index))||WorldView.count(materials.get(index))!=beforeInsert-1)throw new IllegalStateException("Normal infusion insertion was not confirmed; no click was repeated");
                index++;sub=0;report("input_confirmed");return false;
            }
            if(phase==2){
                if(!near(lever,controller))return false;
                for(int i=0;i<9;i++){ItemStack current=display(targets.get(i)).getStackInSlot(0);if(current.getCount()!=1||!WorldView.material(current).equals(materials.get(i)))throw new IllegalStateException("Recipe displays differ before activation");}
                if(powered())throw new IllegalStateException("Activation lever unexpectedly changed");emptyHand();Actions.useBlock(lever,Direction.UP);activated=true;phase=3;wait=20;report("activation_sent");return false;
            }
            if(phase==3){ItemStack output=display(pos).getStackInSlot(1);if(output.isEmpty()){wait=5;return false;}if(output.getCount()!=1||!WorldView.material(output).equals(product))throw new IllegalStateException("Infusion produced another item");phase=4;}
            if(phase==4){
                if(!retrieved){if(!near(pos,controller))return false;emptyHand();Actions.useBlock(pos,Direction.UP);retrieved=true;wait=20;return false;}
                if(WorldView.count(product)!=target||!display(pos).getStackInSlot(0).isEmpty()||!display(pos).getStackInSlot(1).isEmpty())throw new IllegalStateException("Infusion output pickup was not confirmed");
                for(BlockPos pedestal:positions(pos))if(!display(pedestal).getStackInSlot(0).isEmpty())throw new IllegalStateException("Unexpected pedestal input remains after infusion");phase=5;
            }
            if(phase==5){if(powered()){if(!near(lever,controller))return false;emptyHand();Actions.useBlock(lever,Direction.UP);wait=8;return false;}report("completed_verified");return true;}
            return false;
        }
        public void cancel(){if(approach!=null)approach.cancel();Actions.releaseControls();if(initialized)try{report("interrupted_needs_replan");}catch(Exception ignored){}}
    }
}
