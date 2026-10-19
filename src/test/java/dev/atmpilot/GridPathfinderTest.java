package dev.atmpilot;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GridPathfinderTest {
    @Test void goesAroundBlockedCells() {
        var start=new GridPathfinder.Cell(0,0,0); var goal=new GridPathfinder.Cell(3,0,0);
        var blocked=Set.of(new GridPathfinder.Cell(1,0,0),new GridPathfinder.Cell(2,0,0));
        var path=GridPathfinder.find(start,goal,c->c.y()==0&&!blocked.contains(c),1000);
        assertEquals(goal,path.getLast()); assertTrue(path.stream().noneMatch(blocked::contains));
    }
    @Test void refusesUnloadedDestination() {
        assertThrows(IllegalArgumentException.class,()->GridPathfinder.find(
            new GridPathfinder.Cell(0,0,0),new GridPathfinder.Cell(10,0,0),c->c.x()<5,100));
    }
    @Test void searchBudgetIsEnforced() {
        assertThrows(IllegalStateException.class,()->GridPathfinder.find(
            new GridPathfinder.Cell(0,0,0),new GridPathfinder.Cell(90,0,0),c->c.y()==0,2));
    }
}
