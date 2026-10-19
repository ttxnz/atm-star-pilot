package dev.atmpilot;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.atmpilot.PlanModel.*;

class DependencyPlannerTest {
    private static Material m(String s) { return Material.item(s); }
    private static Recipe r(String id, List<Ingredient> in, String out, long n) {
        return new Recipe(id,"minecraft:crafting",in,List.of(new Amount(m(out),n)),List.of(),true,true,"");
    }
    @Test void sharedIngredientsAreNotDoubleSpent() {
        var recipes = List.of(r("a",List.of(Ingredient.of("iron",2)),"a",1),
            r("b",List.of(Ingredient.of("iron",2)),"b",1),
            r("star",List.of(Ingredient.of("a",1),Ingredient.of("b",1)),"star",1));
        Plan p = new DependencyPlanner(recipes).plan(m("star"),1,Map.of(m("iron"),3L));
        assertEquals(1L,p.missing().get(m("iron"))); assertFalse(p.ready());
    }
    @Test void batchSurplusIsReused() {
        var recipes = List.of(r("wire",List.of(Ingredient.of("iron",1)),"wire",4),
            r("star",List.of(Ingredient.of("wire",3),Ingredient.of("wire",1)),"star",1));
        Plan p = new DependencyPlanner(recipes).plan(m("star"),1,Map.of(m("iron"),1L));
        assertTrue(p.ready()); assertEquals(2,p.steps().size());
    }
    @Test void compressionCyclesCannotInventResources() {
        var recipes = List.of(r("pack",List.of(Ingredient.of("iron",9)),"block",1),
            r("unpack",List.of(Ingredient.of("block",1)),"iron",9));
        Plan p = new DependencyPlanner(recipes).plan(m("block"),1,Map.of());
        assertFalse(p.ready()); assertFalse(p.issues().isEmpty());
    }
    @Test void alternativeUsesExistingStock() {
        Ingredient woods = new Ingredient(List.of(new Amount(m("oak"),1),new Amount(m("birch"),1)));
        Plan p = new DependencyPlanner(List.of(r("stick",List.of(woods),"stick",4)))
            .plan(m("stick"),4,Map.of(m("birch"),1L));
        assertTrue(p.ready()); assertEquals("birch",p.steps().getFirst().inputs().getFirst().material().id());
    }
    @Test void awakenedAndOrdinaryAlloysRemainDistinct() {
        Material awakened = new Material("alloy","{\"enchantments\":\"unbreaking:1\"}");
        Recipe star = new Recipe("star","altar",List.of(new Ingredient(List.of(new Amount(awakened,2)))),
            List.of(new Amount(m("star"),1)),List.of(),true,false,"");
        Plan p = new DependencyPlanner(List.of(star)).plan(m("star"),1,Map.of(m("alloy"),2L));
        assertEquals(2L,p.missing().get(awakened)); assertFalse(p.ready());
    }
    @Test void chanceRecipesDoNotPromiseGuaranteedOutput() {
        Recipe chance = new Recipe("rare","crusher",List.of(Ingredient.of("ore",1)),
            List.of(new Amount(m("rare"),1)),List.of(),false,false,"10 percent chance");
        Plan p = new DependencyPlanner(List.of(chance)).plan(m("rare"),1,Map.of(m("ore"),10L));
        assertFalse(p.ready()); assertEquals(1L,p.missing().get(m("rare")));
    }
    @Test void stationIsRequiredOnceAndNotConsumedForEachBatch() {
        Recipe recipe = new Recipe("plate","press",List.of(Ingredient.of("iron",1)),
            List.of(new Amount(m("plate"),1)),List.of(Ingredient.of("press",1)),true,false,"");
        Plan p = new DependencyPlanner(List.of(recipe)).plan(m("plate"),100,Map.of(m("iron"),100L,m("press"),1L));
        assertTrue(p.missing().isEmpty()); assertEquals(100,p.steps().getFirst().batches());
    }
    @Test void coProductsAreAccountedFor() {
        Recipe split = new Recipe("split","craft",List.of(Ingredient.of("ore",1)),
            List.of(new Amount(m("a"),1),new Amount(m("b"),1)),List.of(),true,true,"");
        Recipe star = r("star",List.of(Ingredient.of("a",1),Ingredient.of("b",1)),"star",1);
        assertTrue(new DependencyPlanner(List.of(split,star)).plan(m("star"),1,Map.of(m("ore"),1L)).ready());
    }
    @Test void placedStationDoesNotBecomeConsumableInventory() {
        Recipe plate=new Recipe("plate","press",List.of(Ingredient.of("iron",1)),
            List.of(new Amount(m("plate"),1)),List.of(Ingredient.of("press",1)),true,false,"");
        Plan p=new DependencyPlanner(List.of(plate)).plan(m("plate"),1,Map.of(m("iron"),1L),Set.of(m("press")));
        assertTrue(p.missing().isEmpty());
        Plan missingItem=new DependencyPlanner(List.of()).plan(m("press"),1,Map.of(),Set.of(m("press")));
        assertEquals(1L,missingItem.missing().get(m("press")));
    }
}
