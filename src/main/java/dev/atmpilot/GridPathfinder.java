package dev.atmpilot;

import java.util.*;

/** Bounded A* over walkable cells; unloaded or dangerous terrain is an obstacle. */
public final class GridPathfinder {
    public record Cell(int x,int y,int z) {
        public double distance(Cell c) { return Math.abs(x-c.x)+Math.abs(z-c.z)+Math.abs(y-c.y)*1.5; }
    }
    public interface Terrain { boolean canStand(Cell cell); }
    private record Node(Cell cell,double g,double f) {}
    public static List<Cell> find(Cell start,Cell goal,Terrain terrain,int limit) {
        if(!terrain.canStand(goal)) throw new IllegalArgumentException("Destination is not walkable");
        PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<Cell,Double> cost=new HashMap<>(); Map<Cell,Cell> parent=new HashMap<>();
        cost.put(start,0.0); open.add(new Node(start,0,start.distance(goal)));
        int visited=0;
        while(!open.isEmpty()&&visited++<limit) {
            Node n=open.poll(); if(n.g>cost.getOrDefault(n.cell,Double.POSITIVE_INFINITY)) continue;
            if(n.cell.equals(goal)) {
                LinkedList<Cell> path=new LinkedList<>(); Cell p=goal;
                while(!p.equals(start)) { path.addFirst(p); p=parent.get(p); }
                return path;
            }
            for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                for(int dy:new int[]{0,1,-1,-2,-3}) {
                    Cell next=new Cell(n.cell.x+direction[0],n.cell.y+dy,n.cell.z+direction[1]);
                    if(start.distance(next)>96||!terrain.canStand(next)) continue;
                    double nextCost=n.g+1+Math.abs(dy)*0.5;
                    if(nextCost<cost.getOrDefault(next,Double.POSITIVE_INFINITY)) {
                        cost.put(next,nextCost); parent.put(next,n.cell);
                        open.add(new Node(next,nextCost,nextCost+next.distance(goal)));
                    }
                    break;
                }
            }
        }
        throw new IllegalStateException("No safe loaded path found within "+limit+" nodes");
    }
}
