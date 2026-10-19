package dev.atmpilot.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.atmpilot.runtime.FlightPathfinder.Cell;

class FlightPathfinderTest {
    @Test void descendsAroundPlatformEdgeBeforeApproachingUnderneath(){
        var start=new Cell(0,6,0);var goal=new Cell(0,0,0);
        var path=FlightPathfinder.find(start,goal,(a,b)->{
            // Two-block-tall body cannot cross the glass floor at y=3.
            return !(Math.abs(b.x())<=2&&Math.abs(b.z())<=2&&(b.y()==2||b.y()==3));
        },4096);
        assertFalse(path.isEmpty());assertEquals(goal,path.getLast());
        assertTrue(path.stream().anyMatch(p->Math.abs(p.x())>=3||Math.abs(p.z())>=3));
        for(int i=1;i<path.size();i++)assertEquals(1,path.get(i).distance(path.get(i-1)));
    }
    @Test void solidOrUnloadedCeilingDoesNotBecomeAnImaginaryPassage(){
        assertTrue(FlightPathfinder.find(new Cell(0,6,0),new Cell(0,0,0),
            (a,b)->b.y()!=2&&b.y()!=3,4096).isEmpty());
    }
    @Test void doesNotExpandIntoUnobservedCellsOrInventADiagonalThroughACorner(){
        var start=new Cell(0,0,0);var goal=new Cell(1,0,1);
        assertTrue(FlightPathfinder.find(start,goal,(a,b)->b.equals(goal),64).isEmpty());
    }
}
