package dev.atmpilot.vanilla;

import java.util.*;

/** Returns the first actually observed portal edge in a dimension route. */
public final class VanillaRoute {
    private VanillaRoute() {}
    public static Optional<String> firstEdge(String from,String to,Set<String> edges){
        if(from.equals(to))return Optional.empty();
        record Step(String dimension,String first) {}
        var queue=new ArrayDeque<Step>();var seen=new HashSet<String>();queue.add(new Step(from,""));seen.add(from);
        while(!queue.isEmpty()){
            var n=queue.remove();for(String edge:new TreeSet<>(edges)){
                String[] ends=edge.split("->",-1);if(ends.length!=2||!ends[0].equals(n.dimension)||!seen.add(ends[1]))continue;
                String first=n.first.isEmpty()?edge:n.first;if(ends[1].equals(to))return Optional.of(first);queue.add(new Step(ends[1],first));
            }
        }return Optional.empty();
    }
}
