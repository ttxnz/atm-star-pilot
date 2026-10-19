package dev.atmpilot.runtime;

import dev.atmpilot.PlanModel.*;
import java.util.*;

/** Bounded route selection with cycle-aware estimates, followed by a transactional material ledger. */
public final class FastPlanner {
    private static final double UNKNOWN=1_000_000,IMPOSSIBLE=Double.POSITIVE_INFINITY;
    private final Map<Material,List<Recipe>> producers=new HashMap<>();
    private final Map<Material,Double> estimates=new HashMap<>();
    private Map<Material,Long> initialStock=Map.of();
    private Set<Material> initialStations=Set.of();
    private int visits,estimateVisits;

    public FastPlanner(Collection<Recipe> recipes){
        for(var recipe:recipes){
            if(!recipe.deterministic()||recipe.inputs().isEmpty())continue;
            for(var output:recipe.outputs())producers.computeIfAbsent(output.material(),ignored->new ArrayList<>()).add(recipe);
        }
    }
    public Plan plan(Material target,long count,Map<Material,Long> stock,Set<Material> stations){
        if(count<1||count>1_000_000_000_000L)throw new IllegalArgumentException("Invalid target quantity");
        initialStock=stock;initialStations=stations;visits=0;estimateVisits=0;estimates.clear();
        Ledger ledger=new Ledger();stock.forEach((item,quantity)->{if(quantity>0)ledger.stock.put(item,quantity);});
        ledger.stations.addAll(stations);
        require(target,count,ledger,new LinkedHashSet<>(),0);
        return new Plan(target,count,List.copyOf(ledger.steps),Map.copyOf(ledger.missing),List.copyOf(ledger.issues),
            ledger.missing.isEmpty()&&ledger.issues.isEmpty()&&ledger.steps.stream().allMatch(Step::executable));
    }
    private double estimate(Material material,Set<Material> path,int depth){
        if(initialStock.getOrDefault(material,0L)>0)return 0;
        if(path.contains(material))return IMPOSSIBLE;
        Double cached=estimates.get(material);if(cached!=null)return cached;
        if(depth>14||++estimateVisits>80_000)return UNKNOWN;
        var recipes=producers.getOrDefault(material,List.of());
        if(recipes.isEmpty()){estimates.put(material,1000.0);return 1000;}
        Set<Material> next=new LinkedHashSet<>(path);next.add(material);
        double best=IMPOSSIBLE;
        // Every producer is considered here, including the real altar behind reversible packing recipes.
        for(var recipe:recipes){
            double cost=1+(recipe.executable()?0:5);
            for(var input:recipe.inputs()){
                double ingredient=IMPOSSIBLE;
                for(var choice:input.alternatives())ingredient=Math.min(ingredient,estimate(choice.material(),next,depth+1)*choice.count());
                cost+=ingredient;
            }
            for(var catalyst:recipe.catalysts()){
                double station=IMPOSSIBLE;
                for(var choice:catalyst.alternatives()){
                    if(initialStations.contains(choice.material())){station=0;break;}
                    station=Math.min(station,estimate(choice.material(),next,depth+1)*choice.count());
                }
                cost+=station;
            }
            best=Math.min(best,cost/recipe.produces(material));
        }
        // A finite context-dependent estimate is a route hint, never proof of material availability.
        if(Double.isFinite(best))estimates.put(material,best);
        return best;
    }
    private Amount choose(Ingredient ingredient,long batches,Ledger ledger,Set<Material> path,boolean station){
        Amount best=null;double bestCost=IMPOSSIBLE;
        for(var choice:ingredient.alternatives()){
            if(station&&ledger.stations.contains(choice.material()))return choice;
            long needed;
            try{needed=Math.multiplyExact(choice.count(),batches);}catch(ArithmeticException e){continue;}
            long available=ledger.stock.getOrDefault(choice.material(),0L);
            double cost=available>=needed?-1:estimate(choice.material(),path,0)*Math.max(1,needed-available);
            if(best==null||cost<bestCost){best=choice;bestCost=cost;}
        }
        return best;
    }
    private double routeCost(Recipe recipe,Material target,long count,Ledger ledger,Set<Material> path){
        double cost=recipe.executable()?0:5;
        long yield=recipe.produces(target),batches=Math.addExact(count,yield-1)/yield;
        for(var input:recipe.inputs()){
            Amount choice=choose(input,batches,ledger,path,false);
            if(choice==null)return IMPOSSIBLE;
            long required=Math.multiplyExact(choice.count(),batches),available=ledger.stock.getOrDefault(choice.material(),0L);
            if(required>available&&recycles(choice.material(),target,new HashSet<>(),0))return IMPOSSIBLE;
            if(required>available)cost+=estimate(choice.material(),path,0)*(required-available);
        }
        for(var catalyst:recipe.catalysts()){
            Amount choice=choose(catalyst,1,ledger,path,true);
            if(choice==null)return IMPOSSIBLE;
            if(!ledger.stations.contains(choice.material())&&ledger.stock.getOrDefault(choice.material(),0L)<choice.count())
                cost+=estimate(choice.material(),path,0)*choice.count();
        }
        return cost;
    }
    /** Packing/unpacking chains cannot bootstrap the very item being requested. */
    private boolean recycles(Material item,Material target,Set<Material> seen,int depth){
        if(initialStock.getOrDefault(item,0L)>0)return false;
        if(item.equals(target)||!seen.add(item))return true;
        if(depth>32)return false;
        var recipes=producers.getOrDefault(item,List.of());if(recipes.isEmpty())return false;
        for(var recipe:recipes){
            Material only=null;
            for(var ingredient:recipe.inputs())for(var alternative:ingredient.alternatives()){
                if(only==null)only=alternative.material();else if(!only.equals(alternative.material()))return false;
            }
            if(only==null||!recycles(only,target,new HashSet<>(seen),depth+1))return false;
        }
        return true;
    }
    private void require(Material item,long count,Ledger ledger,Set<Material> path,int depth){
        long current=ledger.stock.getOrDefault(item,0L),used=Math.min(current,count),needed=count-used;
        ledger.stock.put(item,current-used);if(needed==0)return;
        if(path.contains(item)){ledger.missing.merge(item,needed,Math::addExact);ledger.issues.add("Recipe cycle at "+item.id());return;}
        if(++visits>20_000||depth>48){ledger.missing.merge(item,needed,Math::addExact);ledger.issues.add("Planning limit at "+item.id());return;}
        List<Recipe> candidates=new ArrayList<>(producers.getOrDefault(item,List.of()));
        if(candidates.isEmpty()){ledger.missing.merge(item,needed,Math::addExact);return;}
        Set<Material> next=new LinkedHashSet<>(path);next.add(item);
        Map<Recipe,Double> costs=new IdentityHashMap<>();
        for(var recipe:candidates){try{costs.put(recipe,routeCost(recipe,item,needed,ledger,next));}catch(ArithmeticException e){costs.put(recipe,IMPOSSIBLE);}}
        candidates.sort(Comparator.comparingDouble((Recipe r)->costs.get(r)).thenComparing(Recipe::id));
        Ledger best=null;int attempts=0;
        for(var recipe:candidates){
            if(++attempts>3)break;
            if(!Double.isFinite(costs.get(recipe)))continue;
            Ledger branch=ledger.copy();
            try{
                long batches=Math.addExact(needed,recipe.produces(item)-1)/recipe.produces(item);
                List<Amount> selected=new ArrayList<>();
                for(var ingredient:recipe.inputs()){
                    var choice=choose(ingredient,batches,branch,next,false);
                    if(choice==null)throw new IllegalStateException("Unresolved ingredient in "+recipe.id());
                    long quantity=Math.multiplyExact(choice.count(),batches);
                    require(choice.material(),quantity,branch,next,depth+1);selected.add(new Amount(choice.material(),quantity));
                }
                for(var catalyst:recipe.catalysts()){
                    var choice=choose(catalyst,1,branch,next,true);
                    if(choice==null)throw new IllegalStateException("Unresolved station in "+recipe.id());
                    if(!branch.stations.contains(choice.material())){
                        require(choice.material(),choice.count(),branch,next,depth+1);branch.stations.add(choice.material());
                    }
                }
                List<Amount> made=new ArrayList<>();
                for(var output:recipe.outputs()){
                    long quantity=Math.multiplyExact(output.count(),batches);
                    branch.stock.merge(output.material(),quantity,Math::addExact);made.add(new Amount(output.material(),quantity));
                }
                branch.stock.compute(item,(ignored,value)->Math.subtractExact(value==null?0:value,needed));
                branch.steps.add(new Step(recipe.id(),recipe.type(),batches,selected,made,recipe.executable(),recipe.note()));
                if(best==null||score(branch)<score(best))best=branch;
                // An acyclic shortage plan is useful; exhausting all alternative trees is not required.
                if(branch.issues.isEmpty())break;
            }catch(ArithmeticException|IllegalStateException e){branch.issues.add(e.toString());if(best==null)best=branch;}
        }
        if(best==null){ledger.missing.merge(item,needed,Math::addExact);ledger.issues.add("No acyclic deterministic route for "+item.id());}
        else ledger.replace(best);
    }
    private static double score(Ledger ledger){
        return ledger.issues.size()*1_000_000_000.0+ledger.missing.values().stream().mapToDouble(v->1+Math.log1p(v)).sum()*1000+ledger.steps.size();
    }
    private static final class Ledger {
        final Map<Material,Long> stock=new LinkedHashMap<>(),missing=new LinkedHashMap<>();
        final Set<Material> stations=new HashSet<>();final List<Step> steps=new ArrayList<>();final Set<String> issues=new LinkedHashSet<>();
        Ledger copy(){var copy=new Ledger();copy.replace(this);return copy;}
        void replace(Ledger other){stock.clear();stock.putAll(other.stock);missing.clear();missing.putAll(other.missing);stations.clear();stations.addAll(other.stations);steps.clear();steps.addAll(other.steps);issues.clear();issues.addAll(other.issues);}
    }
}
