package dev.atmpilot.vanilla;

import dev.atmpilot.Actions;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.phys.*;
import java.util.*;
import static dev.atmpilot.vanilla.VanillaWorld.*;

final class VanillaTasks {
    private VanillaTasks() {}
    static final class Wait implements VanillaTask {int n;Wait(int n){this.n=n;}public boolean tick(){Actions.releaseControls();return --n<=0;}}
    static final class Sequence implements VanillaTask {
        final Deque<VanillaTask> tasks=new ArrayDeque<>();Sequence(VanillaTask... tasks){this.tasks.addAll(List.of(tasks));}
        public boolean tick()throws Exception{if(tasks.isEmpty())return true;if(tasks.peek().tick()){tasks.remove();Actions.releaseControls();}return tasks.isEmpty();}
        public void cancel(){if(!tasks.isEmpty())tasks.peek().cancel();}
        public String describe(){return tasks.isEmpty()?"done":tasks.peek().describe();}
    }
    static final class Mine implements VanillaTask {
        final BlockPos p;BlockStateSnapshot initial;int ticks,settle;boolean sent;
        Mine(BlockPos p){this.p=p.immutable();}
        public String describe(){return "mine "+p.toShortString();}
        public boolean tick(){
            close();if(!loaded(p))throw new IllegalStateException("Mining target unloaded");var state=mc().level.getBlockState(p);
            if(initial==null)initial=new BlockStateSnapshot(id(state));
            if(state.isAir()||!id(state).equals(initial.id)){mc().gameMode.stopDestroyBlock();return ++settle>=12;}settle=0;
            if(++ticks>600)throw new IllegalStateException("Mining denied or too slow at "+p.toShortString());
            if(!mineable(p))throw new IllegalStateException("Missing tool or protected/block entity target");var h=hit(p);if(h==null)throw new IllegalStateException("Mining face out of visible reach");
            int best=-1;float speed=-1;for(int i=0;i<36;i++){var s=mc().player.getInventory().getItem(i);if((!state.requiresCorrectToolForDrops()||s.isCorrectToolForDrops(state))&&s.getDestroySpeed(state)>speed){speed=s.getDestroySpeed(state);best=i;}}
            if(best>=0){Item item=mc().player.getInventory().getItem(best).getItem();if(!equip(s->s.getItem()==item))return false;}
            Actions.lookAt(h.getLocation());if(!sent){mc().gameMode.startDestroyBlock(p,h.getDirection());sent=true;}else mc().gameMode.continueDestroyBlock(p,h.getDirection());
            mc().player.swing(InteractionHand.MAIN_HAND);return false;
        }
        public void cancel(){if(mc().gameMode!=null)mc().gameMode.stopDestroyBlock();Actions.releaseControls();}
    }
    private record BlockStateSnapshot(String id) {}
    static final class Place implements VanillaTask {
        final BlockPos p;final String item;int ticks,wait;String expected;
        Place(BlockPos p,String item){this.p=p.immutable();this.item=item;}
        public String describe(){return "place "+(item==null?"support":item)+" "+p.toShortString();}
        public boolean tick(){
            close();if(!loaded(p))throw new IllegalStateException("Placement unloaded");var state=mc().level.getBlockState(p);
            if(expected!=null&&id(state).equals(expected))return ++wait>8;
            if(++ticks>100)throw new IllegalStateException("Placement not acknowledged at "+p.toShortString());
            if(!state.canBeReplaced())throw new IllegalStateException("Placement occupied at "+p.toShortString());
            if(expected!=null)return false;
            if(item==null?!equipBuildingBlock():!equip(item))return false;
            if(!(mc().player.getMainHandItem().getItem() instanceof BlockItem bi))throw new IllegalStateException("No building block");
            expected=BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString();
            if(!place(p)){expected=null;if(ticks>15)throw new IllegalStateException("No reachable placement face");}return false;
        }
    }
    static final class Open implements VanillaTask {
        final BlockPos p;int ticks;Open(BlockPos p){this.p=p.immutable();}
        public boolean tick(){if(mc().player.containerMenu.containerId!=0)return true;if(++ticks>80)throw new IllegalStateException("Container did not open");if(ticks==1){if(!emptyHand()) {ticks=0;return false;}if(!interact(p))throw new IllegalStateException("Container out of reach");}return false;}
    }
    static final class Collect implements VanillaTask {
        final BlockPos p;int ticks;VanillaNavigation nav;Collect(BlockPos p){this.p=p==null?null:p.immutable();}
        public boolean tick()throws Exception{
            if(++ticks>180)return true;
            var drops=entities(8,e->e instanceof ItemEntity&&(p==null||e.blockPosition().distSqr(p)<25));
            if(drops.isEmpty())return ticks>15;
            if(nav==null)nav=new VanillaNavigation(drops.getFirst().blockPosition(),.75);
            if(nav.tick())nav=null;return false;
        }
        public void cancel(){if(nav!=null)nav.cancel();}
    }
    /** Transfer a bounded quantity, never drops the cursor or repeats an uncertain click batch. */
    static final class Put implements VanillaTask {
        final int from,to,count;int menu=-1,index,wait;final List<int[]> clicks=new ArrayList<>();
        Put(int from,int to,int count){this.from=from;this.to=to;this.count=count;}
        public boolean tick(){
            var m=mc().player.containerMenu;
            if(menu<0){menu=m.containerId;if(!m.getCarried().isEmpty()||m.getSlot(from).getItem().getCount()<count)throw new IllegalStateException("Transfer precondition changed");
                var dest=m.getSlot(to);var src=m.getSlot(from).getItem();if(!dest.mayPlace(src)||(!dest.getItem().isEmpty()&&!ItemStack.isSameItemSameComponents(src,dest.getItem()))||dest.getMaxStackSize(src)-dest.getItem().getCount()<count)throw new IllegalStateException("Transfer destination not compatible");
                clicks.add(new int[]{from,0});for(int n=0;n<count;n++)clicks.add(new int[]{to,1});clicks.add(new int[]{from,0});}
            if(m.containerId!=menu)throw new IllegalStateException("Container changed during transfer");if(wait-->0)return false;
            if(index<clicks.size()){var c=clicks.get(index++);mc().gameMode.handleInventoryMouseClick(menu,c[0],c[1],ClickType.PICKUP,mc().player);wait=1;return false;}
            if(!m.getCarried().isEmpty())throw new IllegalStateException("Transfer left a cursor stack; manual recovery required");return true;
        }
    }
    static final class Craft implements VanillaTask {
        final RecipeHolder<?> recipe;final String output;final int target;int phase,ticks,wait,before;
        Craft(RecipeHolder<?> recipe,String output,int target){this.recipe=recipe;this.output=output;this.target=target;}
        public String describe(){return "craft "+output+" to "+target;}
        public boolean tick(){
            if(++ticks>240)throw new IllegalStateException("Vanilla recipe book did not acknowledge "+recipe.id());
            if(count(output)>=target&&mc().player.containerMenu.getCarried().isEmpty())return true;if(wait-->0)return false;
            var menu=mc().player.containerMenu;if(!(menu instanceof InventoryMenu||menu instanceof CraftingMenu))throw new IllegalStateException("Open vanilla crafting grid first");
            if(!menu.getCarried().isEmpty())throw new IllegalStateException("Craft cursor not empty");
            if(phase==0){before=count(output);mc().gameMode.handlePlaceRecipe(menu.containerId,recipe,false);phase=1;wait=8;return false;}
            if(phase==1){var result=menu.getSlot(0).getItem();if(result.isEmpty())return false;if(!id(result).equals(output))throw new IllegalStateException("Recipe result mismatch");mc().gameMode.handleInventoryMouseClick(menu.containerId,0,0,ClickType.QUICK_MOVE,mc().player);phase=2;wait=8;return false;}
            if(count(output)<=before)throw new IllegalStateException("Craft output not received; inventory may be full");phase=0;return false;
        }
    }
    static final class Smelt implements VanillaTask {
        final String input,output;final int target;VanillaTask sub;int phase,ticks,wait;
        Smelt(String input,String output,int target){this.input=input;this.output=output;this.target=target;}
        public String describe(){return "smelt "+input+" -> "+output;}
        public boolean tick()throws Exception{
            if(++ticks>16000)throw new IllegalStateException("Smelting timeout; preserve furnace contents");
            if(count(output)>=target)return true;if(sub!=null){if(sub.tick()){sub=null;phase++;}return false;}if(wait-->0)return false;
            var menu=mc().player.containerMenu;if(!(menu instanceof FurnaceMenu))throw new IllegalStateException("Expected the own furnace menu");
            if(phase==0){var inputSlot=menu.getSlot(0);if(!inputSlot.getItem().isEmpty()){if(!id(inputSlot.getItem()).equals(input))throw new IllegalStateException("Furnace has different input");phase++;return false;}
                int source=menuSlot(input);if(source<0)throw new IllegalStateException("Smelting input missing");sub=new Put(source,0,Math.min(Math.min(64,target-count(output)),menu.getSlot(source).getItem().getCount()));return false;}
            if(phase==1){if(!menu.getSlot(1).getItem().isEmpty()){phase++;return false;}int fuel=-1;
                for(var s:menu.slots)if(s.container==mc().player.getInventory()&&(id(s.getItem()).equals("minecraft:coal")||id(s.getItem()).equals("minecraft:charcoal")||isLog(s.getItem())||id(s.getItem()).endsWith("_planks"))){fuel=s.index;break;}
                if(fuel<0)throw new IllegalStateException("Furnace fuel missing");sub=new Put(fuel,1,Math.min(16,menu.getSlot(fuel).getItem().getCount()));return false;}
            if(!menu.getSlot(1).hasItem()&&menu.getSlot(0).hasItem()){phase=1;return false;}
            if(!menu.getSlot(2).getItem().isEmpty()){if(!id(menu.getSlot(2).getItem()).equals(output))throw new IllegalStateException("Furnace output mismatch");mc().gameMode.handleInventoryMouseClick(menu.containerId,2,0,ClickType.QUICK_MOVE,mc().player);wait=8;}
            if(menu.getSlot(0).getItem().isEmpty()&&menu.getSlot(2).getItem().isEmpty()&&count(output)<target){phase=0;wait=10;}return false;
        }
    }
    static int menuSlot(String id){for(var s:mc().player.containerMenu.slots)if(s.container==mc().player.getInventory()&&id(s.getItem()).equals(id))return s.index;return -1;}
    static final class Eat implements VanillaTask {
        int ticks;public boolean tick(){close();Actions.releaseControls();if(mc().player.getFoodData().getFoodLevel()>=19)return true;
            if(!equip(VanillaWorld::isFood))throw new IllegalStateException("No food to eat");if(!mc().player.isUsingItem())mc().gameMode.useItem(mc().player,InteractionHand.MAIN_HAND);mc().options.keyUse.setDown(true);
            if(++ticks>160)throw new IllegalStateException("Eating not acknowledged");return false;}
    }
    static final class Fight implements VanillaTask {
        final UUID targetId;final boolean ranged;VanillaNavigation nav;int ticks,draw,quiet;double lastHp=-1;
        Fight(Entity e,boolean ranged){targetId=e.getUUID();this.ranged=ranged;}
        public String describe(){return (ranged?"bow ":"melee ")+targetId;}
        public boolean tick()throws Exception{
            close();var all=entities(112,e->e.getUUID().equals(targetId));if(all.isEmpty())return ++quiet>20;
            Entity target=all.getFirst();if(!target.isAlive()||target instanceof LivingEntity l&&l.getHealth()<=0)return true;
            if(target instanceof net.minecraft.world.entity.player.Player||target.hasCustomName())throw new IllegalStateException("Protected combat target");
            if(target instanceof net.minecraft.world.entity.TamableAnimal pet&&pet.isTame())throw new IllegalStateException("Tamed target protected");
            if(++ticks>1800)throw new IllegalStateException("Combat target did not die");
            var player=mc().player;boolean bow=ranged&&count("minecraft:arrow")>0&&slot(s->s.getItem() instanceof BowItem)>=0;
            if(target instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal&&!bow)throw new IllegalStateException("Crystal requires arrows; no melee explosion attempt");
            if(bow){
                Actions.releaseControls();if(!equip(s->s.getItem() instanceof BowItem))return false;
                var aim=target instanceof EnderDragon dragon?dragon.head.getBoundingBox().getCenter():target.getBoundingBox().getCenter();
                var vel=target.getDeltaMovement();double dist=player.getEyePosition().distanceTo(aim);aim=aim.add(vel.scale(Math.min(18,dist/2.7)));
                var delta=aim.subtract(player.getEyePosition().add(0,-.1,0));var angle=VanillaMath.arrowAngle(delta.horizontalDistance(),delta.y);
                if(angle.isEmpty())return false;
                Actions.look((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)),(float)-Math.toDegrees(angle.get()));
                if(!player.hasLineOfSight(target)){draw=0;if(player.isUsingItem())mc().gameMode.releaseUsingItem(player);if(nav==null)nav=new VanillaNavigation(target.blockPosition(),8);nav.tick();return false;}
                if(draw++==0)mc().gameMode.useItem(player,InteractionHand.MAIN_HAND);mc().options.keyUse.setDown(true);
                if(draw>=23){mc().options.keyUse.setDown(false);mc().gameMode.releaseUsingItem(player);draw=0;}return false;
            }
            if(target instanceof EnderDragon dragon)target=dragon.head;
            Entity victim=target;
            if(!equip(s->s.getItem() instanceof SwordItem||s.getItem() instanceof AxeItem)){if(slot(s->s.getItem() instanceof SwordItem||s.getItem() instanceof AxeItem)>=0)return false;}
            var box=victim.getBoundingBox();var eye=player.getEyePosition();var nearest=new Vec3(net.minecraft.util.Mth.clamp(eye.x,box.minX,box.maxX),net.minecraft.util.Mth.clamp(eye.y,box.minY,box.maxY),net.minecraft.util.Mth.clamp(eye.z,box.minZ,box.maxZ));
            if(eye.distanceTo(nearest)>player.entityInteractionRange()-.2||!player.hasLineOfSight(victim)){
                if(target instanceof net.minecraft.world.entity.boss.EnderDragonPart)return false;
                if(nav==null||ticks%40==0)nav=new VanillaNavigation(victim.blockPosition(),2);nav.tick();return false;
            }
            Actions.releaseControls();Actions.lookAt(victim.getBoundingBox().getCenter());
            if(player.getAttackStrengthScale(0)>=.97){if(player.isUsingItem())mc().gameMode.releaseUsingItem(player);mc().gameMode.attack(player,victim);player.swing(InteractionHand.MAIN_HAND);}
            else if(player.getOffhandItem().is(Items.SHIELD)&&target instanceof net.minecraft.world.entity.monster.Monster){mc().gameMode.useItem(player,InteractionHand.OFF_HAND);mc().options.keyUse.setDown(true);}return false;
        }
        public void cancel(){if(mc().player.isUsingItem())mc().gameMode.releaseUsingItem(mc().player);if(nav!=null)nav.cancel();Actions.releaseControls();}
    }
    static final class Use implements VanillaTask {
        final String item;final BlockPos block;int ticks;Use(String item,BlockPos block){this.item=item;this.block=block;}
        public boolean tick(){close();if(++ticks>50)return true;if(ticks>1)return false;if(!equip(item)){ticks=0;return false;}
            if(block==null)mc().gameMode.useItem(mc().player,InteractionHand.MAIN_HAND);else if(!interact(block))throw new IllegalStateException("Use target out of reach");return false;}
    }
}
