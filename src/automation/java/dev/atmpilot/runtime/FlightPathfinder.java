package dev.atmpilot.runtime;

import java.util.*;
import java.util.function.BiPredicate;

/** Small loaded-space detours only. It never mines, teleports or assumes an unseen cell is air. */
public final class FlightPathfinder {
    public record Cell(int x,int y,int z) {
        Cell offset(int dx,int dy,int dz){return new Cell(x+dx,y+dy,z+dz);}
        int distance(Cell other){return Math.abs(x-other.x)+Math.abs(y-other.y)+Math.abs(z-other.z);}
    }
    private record Node(Cell cell,int cost,int estimate) {}
    private FlightPathfinder(){}
    public static List<Cell> find(Cell start,Cell goal,BiPredicate<Cell,Cell> clear,int maxVisited){
        if(start.distance(goal)>64||maxVisited<1||maxVisited>4096)return List.of();
        var queue=new PriorityQueue<Node>(Comparator.comparingInt((Node n)->n.cost+n.estimate).thenComparingInt(Node::estimate));
        var cost=new HashMap<Cell,Integer>();var parent=new HashMap<Cell,Cell>();
        queue.add(new Node(start,0,start.distance(goal)));cost.put(start,0);int visited=0;
        int[][] offsets={{0,-1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0}};
        while(!queue.isEmpty()&&visited++<maxVisited){
            var node=queue.remove();if(node.cost!=cost.getOrDefault(node.cell,Integer.MAX_VALUE))continue;
            if(node.cell.equals(goal)){
                var path=new ArrayList<Cell>();Cell at=goal;path.add(at);
                while(!at.equals(start)){at=parent.get(at);path.add(at);}Collections.reverse(path);return path;
            }
            for(var d:offsets){
                Cell next=node.cell.offset(d[0],d[1],d[2]);
                if(next.x<Math.min(start.x,goal.x)-12||next.x>Math.max(start.x,goal.x)+12
                    ||next.z<Math.min(start.z,goal.z)-12||next.z>Math.max(start.z,goal.z)+12
                    ||next.y<Math.min(start.y,goal.y)-4||next.y>Math.max(start.y,goal.y)+8)continue;
                int nextCost=node.cost+1;if(nextCost>=cost.getOrDefault(next,Integer.MAX_VALUE)||!clear.test(node.cell,next))continue;
                cost.put(next,nextCost);parent.put(next,node.cell);queue.add(new Node(next,nextCost,next.distance(goal)));
            }
        }
        return List.of();
    }
}
