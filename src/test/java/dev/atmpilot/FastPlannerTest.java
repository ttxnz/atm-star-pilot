package dev.atmpilot;

import dev.atmpilot.runtime.FastPlanner;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.atmpilot.PlanModel.*;
import static org.junit.jupiter.api.Assertions.*;

class FastPlannerTest {
    static Material m(String id){return Material.item(id);}
    static Recipe recipe(String id,List<Ingredient> input,String output,long count){return new Recipe(id,"craft",input,List.of(new Amount(m(output),count)),List.of(),true,true,"");}
    @Test void reachesRealStarRecipeBehindNineCompressionCycles(){
        List<Recipe> recipes=new ArrayList<>();String previous="star";
        for(int i=0;i<9;i++){
            String block="star_block_"+i;
            recipes.add(recipe("pack_"+i,List.of(Ingredient.of(previous,9)),block,1));
            recipes.add(recipe("unpack_"+i,List.of(Ingredient.of(block,1)),previous,9));previous=block;
        }
        recipes.add(new Recipe("altar","star_altar",List.of(Ingredient.of("alloy",28),Ingredient.of("essence",1)),
            List.of(new Amount(m("star"),1)),List.of(),true,false,""));
        Plan plan=new FastPlanner(recipes).plan(m("star"),1,Map.of(),Set.of());
        assertTrue(plan.issues().isEmpty(),plan.issues().toString());
        assertEquals(28L,plan.missing().get(m("alloy")));assertEquals(1L,plan.missing().get(m("essence")));
        assertEquals("altar",plan.steps().getLast().recipe());
    }
    @Test void usesPlacedCraftingTableAmongManyCatalystAlternatives(){
        Ingredient station=new Ingredient(List.of(new Amount(m("aaa_complex_terminal"),1),new Amount(m("crafting_table"),1)));
        Recipe recipe=new Recipe("planks","craft",List.of(Ingredient.of("log",1)),List.of(new Amount(m("planks"),4)),List.of(station),true,true,"");
        Plan plan=new FastPlanner(List.of(recipe)).plan(m("planks"),4,Map.of(m("log"),1L),Set.of(m("crafting_table")));
        assertTrue(plan.ready());assertTrue(plan.missing().isEmpty());
    }
    @Test void sharedMaterialsSurplusAndComponentsRemainReal(){
        Recipe wire=recipe("wire",List.of(Ingredient.of("iron",1)),"wire",4);
        Recipe star=recipe("star",List.of(Ingredient.of("wire",3),Ingredient.of("wire",1),Ingredient.of("iron",1)),"star",1);
        Plan plan=new FastPlanner(List.of(wire,star)).plan(m("star"),1,Map.of(m("iron"),1L),Set.of());
        assertEquals(1L,plan.missing().get(m("iron")));assertEquals(2,plan.steps().size());
        Material awakened=new Material("alloy","awakened");
        Recipe special=new Recipe("special","altar",List.of(new Ingredient(List.of(new Amount(awakened,2)))),List.of(new Amount(m("star"),1)),List.of(),true,false,"");
        assertEquals(2L,new FastPlanner(List.of(special)).plan(m("star"),1,Map.of(m("alloy"),2L),Set.of()).missing().get(awakened));
    }
    @Test void closedCyclesAndChanceRecipesNeverProduceFreeStock(){
        var a=recipe("a",List.of(Ingredient.of("b",1)),"a",1);var b=recipe("b",List.of(Ingredient.of("a",1)),"b",1);
        Plan plan=new FastPlanner(List.of(a,b)).plan(m("a"),1,Map.of(),Set.of());assertFalse(plan.ready());assertFalse(plan.missing().isEmpty());
        Recipe chance=new Recipe("chance","machine",List.of(Ingredient.of("ore",1)),List.of(new Amount(m("rare"),1)),List.of(),false,false,"");
        assertEquals(1L,new FastPlanner(List.of(chance)).plan(m("rare"),1,Map.of(m("ore"),100L),Set.of()).missing().get(m("rare")));
    }
}
