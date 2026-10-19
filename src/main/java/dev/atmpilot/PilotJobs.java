package dev.atmpilot;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;

public final class PilotJobs {
    private PilotJobs() {}
    private interface Operation { void run() throws Exception; }
    private static PilotController.Job once(String name,Operation op) {
        return new PilotController.Job() {
            public boolean tick(PilotController c)throws Exception { op.run();return true; }
            public String description(){return name;}
        };
    }
    public static PilotController.Job create(JsonObject data) {
        String type=Jsons.str(data,"type","");
        if(type.startsWith("extension:")){
            java.nio.file.Path plugin=PilotMod.CONTROLLER.directory.resolve("runtime/automation.jar"),snapshot=null;
            java.net.URLClassLoader loader=null;
            try{
                if(!java.nio.file.Files.isRegularFile(plugin))throw new IllegalStateException("Automation module is not installed");
                java.nio.file.Path cache=plugin.getParent().resolve("cache");java.nio.file.Files.createDirectories(cache);
                snapshot=java.nio.file.Files.createTempFile(cache,"job-",".jar");
                java.nio.file.Files.copy(plugin,snapshot,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                loader=new java.net.URLClassLoader(new java.net.URL[]{snapshot.toUri().toURL()},PilotJobs.class.getClassLoader());
                Class<?> module=Class.forName("dev.atmpilot.runtime.AutomationPlugin",true,loader);
                return (PilotController.Job)module.getMethod("create",JsonObject.class,java.net.URLClassLoader.class,java.nio.file.Path.class)
                    .invoke(null,data.deepCopy(),loader,snapshot);
            }catch(Exception|LinkageError e){
                if(loader!=null)try{loader.close();}catch(java.io.IOException ignored){}
                if(snapshot!=null)try{java.nio.file.Files.deleteIfExists(snapshot);}catch(java.io.IOException ignored){}
                Throwable cause=e instanceof java.lang.reflect.InvocationTargetException&&e.getCause()!=null?e.getCause():e;
                throw new IllegalStateException("Automation job module failed: "+cause,cause);
            }
        }
        return switch(type) {
            case "wait" -> new Wait(bound(data,"ticks",20,1,72_000));
            case "look" -> once(type,()->Actions.look((float)Jsons.decimal(data,"yaw",0),(float)Jsons.decimal(data,"pitch",0)));
            case "select" -> once(type,()->Actions.select(bound(data,"slot",0,0,8)));
            case "move" -> new Move(data,bound(data,"ticks",20,1,200));
            case "walk" -> new Walk(Actions.pos(data));
            case "walk_near" -> new Approach(Actions.pos(data));
            case "mine" -> new Mine(Actions.pos(data),Jsons.bool(data,"allowBlockEntity",false));
            case "use_block" -> new Open(Actions.pos(data),Jsons.bool(data,"expectMenu",false));
            case "place" -> new Place(Actions.pos(data),Jsons.str(data,"expectedBlock",""),Jsons.bool(data,"aim",true));
            case "use_item" -> new UseItem(bound(data,"ticks",32,1,1200));
            case "attack" -> new Attack(bound(data,"entityId",-1,0,Integer.MAX_VALUE));
            case "inventory" -> once(type,()-> {var mc=Actions.mc();Actions.closeMenu();mc.setScreen(new InventoryScreen(mc.player));});
            case "close" -> once(type,Actions::closeMenu);
            case "click" -> once(type,()-> {
                var mc=Actions.mc();
                if(data.has("stateId")&&data.get("stateId").getAsInt()!=mc.player.containerMenu.getStateId())
                    throw new IllegalStateException("Menu revision changed; observe before retrying");
                Actions.click(bound(data,"containerId",-1,0,Integer.MAX_VALUE),bound(data,"slot",-1,0,100_000),
                    bound(data,"button",0,0,8),ClickType.valueOf(Jsons.str(data,"clickType","PICKUP")));
            });
            case "transfer" -> new MenuTransferJob(bound(data,"containerId",-1,0,Integer.MAX_VALUE),
                bound(data,"from",-1,0,100_000),bound(data,"to",-1,0,100_000),bound(data,"count",1,1,64));
            case "jei_transfer" -> once(type,()->JeiBridge.transfer(Jsons.str(data,"recipe",""),Jsons.bool(data,"max",false)));
            case "craft" -> new Craft(Jsons.str(data,"recipe",""),bound(data,"count",1,1,1_000_000));
            case "machine" -> new MachineJob(data);
            case "blueprint" -> new Blueprint(data.getAsJsonArray("blocks"));
            case "remember" -> once(type,()->PilotMod.CONTROLLER.remember(Jsons.str(data,"location","current")));
            case "screen_click" -> once(type,()->ScreenAccess.click(data));
            case "screen_key" -> once(type,()->ScreenAccess.key(data));
            case "screen_text" -> once(type,()->ScreenAccess.text(Jsons.str(data,"text","")));
            case "quest_open" -> once(type,()->QuestAccess.open(Jsons.str(data,"quest","")));
            case "menu_button" -> once(type,()-> {
                ServerRules.checkScreenAction();
                var mc=Actions.mc();int id=bound(data,"containerId",-1,0,Integer.MAX_VALUE);
                if(mc.player.containerMenu.containerId!=id) throw new IllegalStateException("Menu changed");
                mc.gameMode.handleInventoryButtonClick(id,bound(data,"button",0,0,255));
            });
            case "assert_inventory" -> once(type,()-> {
                var material=new PlanModel.Material(Jsons.str(data,"item",""),Jsons.str(data,"components",""));
                if(WorldView.count(material)<Jsons.number(data,"count",1)) throw new IllegalStateException("Inventory assertion failed: "+material.id());
            });
            default -> throw new IllegalArgumentException("Unknown action type: "+type);
        };
    }
    private static int bound(JsonObject o,String key,int fallback,int min,int max) {
        int v=Jsons.integer(o,key,fallback);if(v<min||v>max)throw new IllegalArgumentException(key+" must be "+min+".."+max);return v;
    }
    public static final class Wait implements PilotController.Job {
        private int remaining;public Wait(int ticks){remaining=ticks;}
        public boolean tick(PilotController c){return --remaining<=0;}
    }
    private static final class Move implements PilotController.Job {
        private final JsonObject keys;private int remaining;
        Move(JsonObject keys,int ticks){this.keys=keys;remaining=ticks;}
        public boolean tick(PilotController c){
            var mc=Actions.mc(); if(mc.screen!=null)throw new IllegalStateException("Movement requires a closed screen");
            var o=mc.options;
            o.keyUp.setDown(Jsons.bool(keys,"forward",false));o.keyDown.setDown(Jsons.bool(keys,"back",false));
            o.keyLeft.setDown(Jsons.bool(keys,"left",false));o.keyRight.setDown(Jsons.bool(keys,"right",false));
            o.keyJump.setDown(Jsons.bool(keys,"jump",false));o.keyShift.setDown(Jsons.bool(keys,"sneak",false));
            o.keySprint.setDown(Jsons.bool(keys,"sprint",false));return --remaining<0;
        }
    }
    private static boolean stand(GridPathfinder.Cell cell) {
        var mc=Actions.mc();BlockPos p=new BlockPos(cell.x(),cell.y(),cell.z());
        if(!mc.level.hasChunkAt(p)||!mc.level.hasChunkAt(p.above()))return false;
        for(BlockPos part:List.of(p,p.above(),p.below())) {
            var state=mc.level.getBlockState(part);String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if(!state.getFluidState().isEmpty()||id.contains("lava")||id.contains("fire")||id.contains("cactus")||id.contains("magma")||id.contains("powder_snow"))return false;
        }
        return mc.level.getBlockState(p).getCollisionShape(mc.level,p).isEmpty()
            &&mc.level.getBlockState(p.above()).getCollisionShape(mc.level,p.above()).isEmpty()
            &&mc.level.getBlockState(p.below()).isFaceSturdy(mc.level,p.below(),Direction.UP);
    }
    private static GridPathfinder.Cell cell(BlockPos p){return new GridPathfinder.Cell(p.getX(),p.getY(),p.getZ());}
    static BlockPos near(BlockPos target) {
        var mc=Actions.mc();BlockPos start=mc.player.blockPosition();List<BlockPos> candidates=new ArrayList<>();
        BlockPos interaction=target;Direction face=Direction.UP;
        boolean placing=mc.level.getBlockState(target).canBeReplaced();
        if(placing)for(Direction possible:Direction.values()) {
            BlockPos support=target.relative(possible.getOpposite());
            if(LoadedWorld.has(support)&&!mc.level.getBlockState(support).getCollisionShape(mc.level,support).isEmpty()) {
                interaction=support;face=possible;break;
            }
        }
        Vec3 hit=Vec3.atCenterOf(interaction).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.49));
        float eyeHeight=placing?mc.player.getEyeHeight(net.minecraft.world.entity.Pose.CROUCHING):mc.player.getEyeHeight();
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int dy:new int[]{0,1,-1,2}) {
            BlockPos p=new BlockPos(target.getX()+dx,start.getY()+dy,target.getZ()+dz);
            if(p.getX()==target.getX()&&p.getZ()==target.getZ())continue;
            Vec3 eye=Vec3.atBottomCenterOf(p).add(0,eyeHeight,0);
            if(!stand(cell(p))||eye.distanceTo(hit)>3.8)continue;
            // Walk accepts a small distance from the cell center. A center-only ray can just graze a corner
            // while the actual stopped position is obstructed. Check the full accepted stance margin.
            boolean visible=true;
            for(double ox:new double[]{-0.35,0,0.35})for(double oz:new double[]{-0.35,0,0.35}) {
                Vec3 possibleEye=eye.add(ox,0,oz);
                var trace=mc.level.clip(new ClipContext(possibleEye,hit,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
                if(possibleEye.distanceTo(hit)>3.8||(trace.getType()==HitResult.Type.BLOCK&&!trace.getBlockPos().equals(interaction)))visible=false;
            }
            if(visible)candidates.add(p);
        }
        candidates.sort(Comparator.comparingDouble(p->p.distSqr(start)));
        for(BlockPos p:candidates.stream().limit(12).toList()) {
            try{GridPathfinder.find(cell(start),cell(p),PilotJobs::stand,6000);return p;}catch(IllegalArgumentException|IllegalStateException ignored){}
        }
        throw new IllegalStateException("No safe visible approach position for "+target);
    }
    static final class Walk implements PilotController.Job {
        private final BlockPos destination;private List<GridPathfinder.Cell> path;private int index,stuck;
        private Vec3 previous;
        Walk(BlockPos destination){this.destination=destination;}
        public String description(){return "Walk to "+destination.toShortString();}
        public boolean tick(PilotController c){
            var mc=Actions.mc();if(mc.screen!=null)throw new IllegalStateException("Close the screen before walking");
            if(path==null)path=GridPathfinder.find(cell(mc.player.blockPosition()),cell(destination),PilotJobs::stand,12_000);
            if(index>=path.size())return true;
            var next=path.get(index);Vec3 target=new Vec3(next.x()+0.5,next.y(),next.z()+0.5);
            Vec3 current=mc.player.position();
            if(current.subtract(target).horizontalDistanceSqr()<0.12&&Math.abs(current.y-target.y)<0.8){index++;Actions.releaseControls();return false;}
            if(previous!=null&&current.distanceToSqr(previous)<0.0004)stuck++;else stuck=0;
            if(stuck>80)throw new IllegalStateException("Path blocked; external replanning required");previous=current;
            Vec3 delta=target.subtract(current);float yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
            float turn=Mth.wrapDegrees(yaw-mc.player.getYRot());
            Actions.look(mc.player.getYRot()+Mth.clamp(turn,-25,25),10);
            mc.options.keyUp.setDown(Math.abs(turn)<50);mc.options.keySprint.setDown(false);
            mc.options.keyJump.setDown(next.y()>mc.player.getY()+0.3&&mc.player.onGround());return false;
        }
    }
    private static final class Approach implements PilotController.Job {
        private final BlockPos target;private Walk walk;
        Approach(BlockPos target){this.target=target;}
        public boolean tick(PilotController c){if(walk==null)walk=new Walk(near(target));return walk.tick(c);}
    }
    private static final class Open implements PilotController.Job {
        private final BlockPos position;private final boolean menu;private int elapsed,initial;
        Open(BlockPos p,boolean menu){position=p;this.menu=menu;}
        public boolean tick(PilotController c){
            var mc=Actions.mc();
            if(elapsed++==0){initial=mc.player.containerMenu.containerId;Actions.useBlock(position,Direction.UP);if(!menu)return true;}
            if(mc.player.containerMenu.containerId!=initial){c.remember(mc.level.dimension().location()+"@"+position.toShortString());return true;}
            if(elapsed>100)throw new IllegalStateException("Block did not open a menu");return false;
        }
    }
    private static final class Place implements PilotController.Job {
        private final BlockPos position;private final String expected;private final boolean aim;private int elapsed;private String item;
        Place(BlockPos position,String expected,boolean aim){this.position=position;this.expected=expected;this.aim=aim;}
        public boolean tick(PilotController c){
            var mc=Actions.mc();String actual=BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(position).getBlock()).toString();
            net.minecraft.world.level.block.Block placed=expected.isEmpty()
                ?(mc.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem blockItem?blockItem.getBlock():mc.level.getBlockState(position).getBlock())
                :BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(expected));
            boolean oriented=false;for(var property:placed.getStateDefinition().getProperties()){
                String name=property.getName();if(name.equals("facing")||name.equals("horizontal_facing")||name.equals("subfacing")||name.equals("rotation")||name.equals("spin"))oriented=true;
            }
            boolean synchronizeAim=aim&&oriented;int clickTick=synchronizeAim?11:6,confirmTick=clickTick+5;
            if(!expected.isEmpty()&&actual.equals(expected)){
                if(elapsed==0||elapsed>=confirmTick)return true;elapsed++;return false;
            }
            if(elapsed==0){
                item=BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString();
                SneakControl.hold();elapsed=1;return false;
            }
            if(elapsed++<=4)return false;
            if(elapsed==6){
                if(synchronizeAim){
                    boolean found=false;for(Direction face:Direction.values()){
                        BlockPos support=position.relative(face.getOpposite());
                        if(LoadedWorld.has(support)&&!mc.level.getBlockState(support).getCollisionShape(mc.level,support).isEmpty()){
                            Actions.lookAt(Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.49)));found=true;break;
                        }
                    }
                    if(!found)throw new IllegalStateException("No observed placement support");
                }else Actions.place(position,aim);
                return false;
            }
            if(synchronizeAim&&elapsed<11)return false;
            if(synchronizeAim&&elapsed==11){
                if(!BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString().equals(item))throw new IllegalStateException("Placement item changed while aiming");
                Actions.place(position,false);return false;
            }
            if(elapsed<confirmTick)return false;
            if(!actual.equals("minecraft:air")&&!mc.level.getBlockState(position).canBeReplaced()) {
                if(!expected.isEmpty()&&!actual.equals(expected))throw new IllegalStateException("Unexpected placed block: "+actual);
                return true;
            }
            if(elapsed>60)throw new IllegalStateException("Placement rejected or item is not a placeable block: "+item);return false;
        }
    }
    private static final class Mine implements PilotController.Job {
        private final BlockPos position;private final boolean allowBlockEntity;private int elapsed;
        private String original;
        Mine(BlockPos p,boolean allowBlockEntity){position=p;this.allowBlockEntity=allowBlockEntity;}
        public boolean tick(PilotController c){
            var mc=Actions.mc();if(mc.screen!=null)throw new IllegalStateException("Close menu before mining");ServerRules.checkBuild(position);Actions.checkReach(position);
            var state=mc.level.getBlockState(position);String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if(state.isAir()||(original!=null&&!original.equals(id))){mc.gameMode.stopDestroyBlock();return true;}
            if(elapsed++==0){
                original=id;if(state.hasBlockEntity()&&!allowBlockEntity)throw new IllegalStateException("Machine/container mining requires allowBlockEntity=true");
                if(state.getDestroySpeed(mc.level,position)<0)throw new IllegalStateException("Unbreakable target");
                int best=mc.player.getInventory().selected;float speed=0;
                for(int i=0;i<9;i++){ItemStack stack=mc.player.getInventory().getItem(i);
                    if(stack.getDestroySpeed(state)>speed&&(!state.requiresCorrectToolForDrops()||stack.isCorrectToolForDrops(state))){best=i;speed=stack.getDestroySpeed(state);}}
                Actions.select(best);
                if(state.requiresCorrectToolForDrops()&&!mc.player.getMainHandItem().isCorrectToolForDrops(state))throw new IllegalStateException("No suitable tool in hotbar");
                Actions.lookAt(Vec3.atCenterOf(position));mc.gameMode.startDestroyBlock(position,Direction.UP);
            }
            mc.gameMode.continueDestroyBlock(position,Direction.UP);mc.player.swing(InteractionHand.MAIN_HAND);
            if(elapsed>1200)throw new IllegalStateException("Mining timeout or protected block");return false;
        }
        public void cancel(){var mc=Minecraft.getInstance();if(mc.gameMode!=null)mc.gameMode.stopDestroyBlock();}
    }
    private static final class UseItem implements PilotController.Job {
        private final int duration;private int elapsed;UseItem(int duration){this.duration=duration;}
        public boolean tick(PilotController c){var mc=Actions.mc();if(elapsed++==0){ServerRules.checkItemUse();mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);}
            if(elapsed>=duration||!mc.player.isUsingItem()){mc.options.keyUse.setDown(false);mc.gameMode.releaseUsingItem(mc.player);return true;}
            mc.options.keyUse.setDown(true);return false;}
        public void cancel(){var mc=Minecraft.getInstance();mc.options.keyUse.setDown(false);if(mc.player!=null&&mc.gameMode!=null)mc.gameMode.releaseUsingItem(mc.player);}
    }
    private static final class Attack implements PilotController.Job {
        private final int id;private int waited;Attack(int id){this.id=id;}
        public boolean tick(PilotController c){var mc=Actions.mc();var entity=mc.level.getEntity(id);
            if(entity==null||!entity.isAlive())return true;
            if(mc.player.distanceTo(entity)>mc.player.entityInteractionRange())throw new IllegalStateException("Entity outside normal attack reach");
            if(mc.player.getAttackStrengthScale(0)<0.95){if(++waited>100)throw new IllegalStateException("Attack cooldown timeout");return false;}
            Actions.lookAt(entity.getEyePosition());mc.gameMode.attack(mc.player,entity);mc.player.swing(InteractionHand.MAIN_HAND);return true;}
    }
    private static final class Craft implements PilotController.Job {
        private final String recipe;private final int requested;private long target,lastCount;private int phase,wait;
        private PlanModel.Material output;
        Craft(String recipe,int requested){this.recipe=recipe;this.requested=requested;}
        public String description(){return "Batch craft "+requested+" via "+recipe;}
        public boolean tick(PilotController c){
            var mc=Actions.mc();var entry=JeiBridge.entry(recipe);
            if(!entry.recipe().type().equals("minecraft:crafting"))throw new IllegalStateException("Craft job requires a crafting-table recipe; use machine job for other types");
            if(!CraftingMenus.supported(mc.player.containerMenu))throw new IllegalStateException("Open a crafting table, personal inventory or own AE crafting terminal first");
            ServerRules.checkScreenAction();
            if(output==null){output=entry.recipe().outputs().getFirst().material();target=CraftOutput.count(output)+requested;}
            if(CraftOutput.count(output)>=target&&mc.player.containerMenu.getCarried().isEmpty())return true;
            if(wait-->0)return false;
            if(phase==0){
                long remaining=target-CraftOutput.count(output);long perBatch=entry.recipe().outputs().getFirst().count();
                JeiBridge.transfer(recipe,remaining>=2*perBatch);phase=1;wait=4;
            }else if(phase==1){
                int resultSlot=CraftingMenus.resultIndex(mc.player.containerMenu);var result=mc.player.containerMenu.getSlot(resultSlot).getItem();
                if(result.isEmpty()||!CraftOutput.matches(output,WorldView.material(result))){
                    if(wait<-100)throw new IllegalStateException("Crafting result did not match the live recipe after waiting for menu synchronization");
                    return false;
                }
                lastCount=CraftOutput.count(output);
                if(resultSlot==0)Actions.click(mc.player.containerMenu.containerId,resultSlot,0,ClickType.QUICK_MOVE);
                else CraftingBatches.takeResult(mc.player.containerMenu,resultSlot,target-lastCount);
                phase=2;wait=8;
            }else{
                var menu=mc.player.containerMenu;ItemStack carried=menu.getCarried();
                if(CraftingMenus.resultIndex(menu)!=0&&!carried.isEmpty()){
                    if(!CraftOutput.matches(output,WorldView.material(carried)))throw new IllegalStateException("AE crafting cursor contains an unexpected result; cursor retained");
                    int empty=-1,destination=-1;
                    for(int i=0;i<menu.slots.size();i++){
                        var slot=menu.getSlot(i);ItemStack held=slot.getItem();
                        if(slot.container!=mc.player.getInventory()||!slot.mayPlace(carried))continue;
                        if(!held.isEmpty()&&!ItemStack.isSameItemSameComponents(held,carried))continue;
                        if(slot.getMaxStackSize(carried)-held.getCount()<carried.getCount())continue;
                        if(!held.isEmpty()){destination=i;break;}if(empty<0)empty=i;
                    }
                    if(destination<0)destination=empty;if(destination<0)throw new IllegalStateException("No room for the AE crafting result; cursor retained");
                    Actions.click(menu.containerId,destination,0,ClickType.PICKUP);wait=8;return false;
                }
                if(CraftOutput.count(output)<=lastCount){
                    if(wait<-200)throw new IllegalStateException("Craft output synchronization timed out; inspect the retained grid, cursor and inventory before retrying");
                    return false;
                }
                phase=0;wait=1;
            }
            return false;
        }
    }
    private static final class Blueprint implements PilotController.Job {
        private final JsonArray blocks;private int index,phase;private PilotController.Job child;private int wait;
        Blueprint(JsonArray blocks){if(blocks==null||blocks.isEmpty()||blocks.size()>4096)throw new IllegalArgumentException("Blueprint requires 1..4096 blocks");this.blocks=blocks.deepCopy();}
        public String description(){return "Build blueprint "+index+" / "+blocks.size();}
        public boolean tick(PilotController c)throws Exception {
            var mc=Actions.mc();if(index>=blocks.size())return true;if(wait-->0)return false;
            if(child!=null){if(child.tick(c)){child=null;phase++;Actions.releaseControls();}return false;}
            JsonObject block=blocks.get(index).getAsJsonObject();BlockPos position=Actions.pos(block);
            ServerRules.checkBuild(position);
            String item=Jsons.str(block,"item","");String expected=Jsons.str(block,"block",item);
            String actual=BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(position).getBlock()).toString();
            if(actual.equals(expected)){verifyProperties(block,position);index++;phase=0;return false;}
            if(phase==0){Actions.closeMenu();child=new Walk(near(position));}
            else if(phase==1){
                int found=-1;for(int i=0;i<36;i++){
                    ItemStack stack=mc.player.getInventory().getItem(i);
                    if(!stack.isEmpty()&&BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)
                        &&(!block.has("components")||WorldView.material(stack).components().equals(block.get("components").getAsString()))){found=i;break;}
                }
                if(found<0)throw new IllegalStateException("Blueprint missing material: "+item+" at "+position);
                if(found>=9){Actions.click(mc.player.inventoryMenu.containerId,found,8,ClickType.SWAP);Actions.select(8);}else Actions.select(found);
                phase=2;wait=8;
            }else if(phase==2){
                if(block.has("yaw")){Actions.look((float)Jsons.decimal(block,"yaw",0),(float)Jsons.decimal(block,"pitch",60));wait=4;phase=3;}
                else{child=new Place(position,expected,true);}
            }else if(phase==3&&block.has("yaw")){child=new Place(position,expected,false);}
            else{verifyProperties(block,position);index++;phase=0;}
            c.note(description());return false;
        }
        public void cancel(){if(child!=null)child.cancel();}
        private void verifyProperties(JsonObject specification,BlockPos position){
            if(!specification.has("properties"))return;
            Map<String,String> actual=new HashMap<>();
            Actions.mc().level.getBlockState(position).getValues().forEach((property,value)->actual.put(property.getName(),value.toString().toLowerCase(Locale.ROOT)));
            for(var entry:specification.getAsJsonObject("properties").entrySet())if(!entry.getValue().getAsString().equals(actual.get(entry.getKey())))
                throw new IllegalStateException("Blueprint orientation/state mismatch at "+position+": "+entry.getKey()+" expected "+entry.getValue()+", actual "+actual.get(entry.getKey()));
        }
    }
}
