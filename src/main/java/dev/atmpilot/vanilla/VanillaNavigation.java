package dev.atmpilot.vanilla;

import dev.atmpilot.Actions;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import static dev.atmpilot.vanilla.VanillaWorld.*;

/** Replans local loaded terrain. Dig/bridge/jump are normal client actions with acknowledgements. */
final class VanillaNavigation implements VanillaTask {
    private record Node(BlockPos p,double g,double f) {}
    private final BlockPos goal;private final double tolerance;private List<BlockPos> path=List.of();private int index,ticks,stuck;
    private Vec3 last;private VanillaTask preparation;private int replan;
    VanillaNavigation(BlockPos goal,double tolerance){this.goal=goal.immutable();this.tolerance=tolerance;}
    public String describe(){return "walk "+goal.toShortString();}
    private boolean viable(BlockPos p){
        if(!loaded(p.above())||!mineable(p)||!mineable(p.above()))return false;
        if(mc().level.getBlockState(p).hasBlockEntity()||mc().level.getBlockState(p.above()).hasBlockEntity())return false;
        return floor(p.below())||clear(p.below())&&blocks()>4;
    }
    private void plan(){
        var start=mc().player.blockPosition();var open=new PriorityQueue<Node>(Comparator.comparingDouble(Node::f));
        var costs=new HashMap<BlockPos,Double>();var parents=new HashMap<BlockPos,BlockPos>();open.add(new Node(start,0,start.distManhattan(goal)));costs.put(start,0.0);
        BlockPos best=start;double nearest=start.distManhattan(goal);int left=1600;
        while(!open.isEmpty()&&left-->0){var n=open.remove();if(n.g>costs.getOrDefault(n.p,1e9))continue;
            double distance=n.p.distManhattan(goal);if(distance<nearest){nearest=distance;best=n.p;}if(n.p.distSqr(goal)<=tolerance*tolerance){best=n.p;break;}
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})for(int dy:new int[]{0,1,-1,-2,-3}){
                var p=n.p.offset(d[0],dy,d[1]);if(p.distManhattan(start)>28||!viable(p))continue;
                if(dy>0&&!clear(n.p.above(2)))continue;
                // A bridge step needs an existing adjacent support and cannot span unseen void.
                if(!floor(p.below())&&!floor(n.p.below()))continue;
                double price=1+Math.abs(dy)*.7+(!clear(p)?5:0)+(!clear(p.above())?5:0)+(!floor(p.below())?8:0);
                double g=n.g+price;if(g<costs.getOrDefault(p,1e9)){costs.put(p,g);parents.put(p,n.p);open.add(new Node(p,g,g+p.distManhattan(goal)));}break;
            }
        }
        if(best.equals(start))throw new IllegalStateException("No safe loaded ground route toward "+goal.toShortString());
        var reversed=new LinkedList<BlockPos>();for(var p=best;!p.equals(start);p=parents.get(p))reversed.addFirst(p);
        path=reversed;index=0;stuck=0;replan=0;
    }
    public boolean tick()throws Exception{
        if(++ticks>3600)throw new IllegalStateException("Ground travel timed out: "+goal.toShortString());
        VanillaWorld.close();var player=mc().player;
        if(player.position().distanceTo(Vec3.atBottomCenterOf(goal))<=tolerance){Actions.releaseControls();return true;}
        if(preparation!=null){if(preparation.tick()){preparation=null;path=List.of();}return false;}
        if(player.isInWater()){mc().options.keyJump.setDown(true);Actions.lookAt(Vec3.atCenterOf(goal));mc().options.keyUp.setDown(true);if(++stuck>160)throw new IllegalStateException("Cannot find dry shore");return false;}
        if(path.isEmpty()||index>=path.size()||replan++>100){plan();return false;}
        var p=path.get(index);if(!loaded(p))throw new IllegalStateException("Travel frontier unloaded");
        if(!clear(p.above())){preparation=new VanillaTasks.Mine(p.above());return false;}
        if(!clear(p)){preparation=new VanillaTasks.Mine(p);return false;}
        if(!floor(p.below())){preparation=new VanillaTasks.Place(p.below(),null);return false;}
        var delta=Vec3.atBottomCenterOf(p).subtract(player.position());
        if(delta.horizontalDistance()<.25&&Math.abs(delta.y)<.6){index++;Actions.releaseControls();return false;}
        float wanted=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));float turn=Mth.wrapDegrees(wanted-player.getYRot());
        Actions.look(player.getYRot()+Mth.clamp(turn,-30,30),15);mc().options.keyUp.setDown(Math.abs(turn)<40);
        mc().options.keySprint.setDown(false);mc().options.keyJump.setDown(delta.y>.25&&player.onGround());
        mc().options.keyShift.setDown(!floor(p.relative(net.minecraft.core.Direction.fromYRot(wanted)).below()));
        if(last!=null&&player.position().distanceToSqr(last)<.0002)stuck++;else stuck=0;last=player.position();
        if(stuck>40){path=List.of();stuck=0;}return false;
    }
    public void cancel(){if(preparation!=null)preparation.cancel();Actions.releaseControls();}
}
