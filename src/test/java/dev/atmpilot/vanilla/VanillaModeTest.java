package dev.atmpilot.vanilla;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VanillaModeTest {
    @Test void observedEyesNeedSeparatedNonparallelForwardRays(){
        var a=new VanillaMath.Ray(0,0,10,10);var b=new VanillaMath.Ray(100,0,-10,10);
        var hit=VanillaMath.intersect(a,b).orElseThrow();assertEquals(50,hit.x(),1e-6);assertEquals(50,hit.z(),1e-6);
        assertTrue(VanillaMath.intersect(a,new VanillaMath.Ray(2,0,-10,10)).isEmpty());
        assertTrue(VanillaMath.intersect(a,new VanillaMath.Ray(100,0,10,10)).isEmpty());
        assertTrue(VanillaMath.intersect(a,new VanillaMath.Ray(100,0,10,-10)).isEmpty());
    }
    @Test void bowRejectsUnreachableTargetsAndCompensatesGravity(){
        assertTrue(VanillaMath.arrowAngle(40,0).orElseThrow()>0);
        assertTrue(VanillaMath.arrowAngle(40,30).orElseThrow()>VanillaMath.arrowAngle(40,0).orElseThrow());
        assertTrue(VanillaMath.arrowAngle(10,-10).orElseThrow()<0);
        assertTrue(VanillaMath.arrowAngle(170,0).isEmpty());
        assertTrue(VanillaMath.arrowAngle(15,300).isEmpty());
        assertTrue(VanillaMath.arrowAngle(Double.NaN,0).isEmpty());
    }
    @Test void expandingSearchDoesNotCircleBackAndVisitsNeighbours(){
        var positions=new HashSet<VanillaMath.Point>();var last=VanillaMath.spiral(0);positions.add(last);
        for(int i=1;i<1000;i++){var p=VanillaMath.spiral(i);assertTrue(positions.add(p),"repeated frontier "+i);assertEquals(1,Math.abs(p.x()-last.x())+Math.abs(p.z()-last.z()));last=p;}
        assertTrue(positions.stream().anyMatch(p->Math.abs(p.x())>=15));
    }
    @Test void noInferredPortalEdgeForCrossDimensionRecovery(){
        Set<String> edges=Set.of("minecraft:overworld->minecraft:the_nether","minecraft:the_nether->minecraft:overworld","minecraft:overworld->minecraft:the_end");
        assertEquals("minecraft:the_nether->minecraft:overworld",VanillaRoute.firstEdge("minecraft:the_nether","minecraft:the_end",edges).orElseThrow());
        assertTrue(VanillaRoute.firstEdge("minecraft:the_end","minecraft:overworld",edges).isEmpty());
        assertTrue(VanillaRoute.firstEdge("minecraft:the_nether","minecraft:the_nether",edges).isEmpty());
    }
    @Test void modeCannotAccidentallyStartInAnExistingOrDifferentWorld(){
        assertThrows(IllegalArgumentException.class,()->VanillaPolicy.validateStart("local:new","local:old",true,true,true,true,false));
        assertThrows(IllegalArgumentException.class,()->VanillaPolicy.validateStart("local:new","local:new",false,true,true,true,false));
        assertThrows(IllegalArgumentException.class,()->VanillaPolicy.validateStart("server:atm","server:atm",true,true,false,true,false));
        assertThrows(IllegalArgumentException.class,()->VanillaPolicy.validateStart("local:new","local:new",true,false,true,true,false));
        assertThrows(IllegalArgumentException.class,()->VanillaPolicy.validateStart("local:old","local:old",true,true,true,false,false));
        assertDoesNotThrow(()->VanillaPolicy.validateStart("local:new","local:new",true,true,true,true,false));
        assertDoesNotThrow(()->VanillaPolicy.validateStart("local:new","local:new",true,true,true,false,true));
    }
    @Test void vanillaActionPolicyHasNoCommandOrFlightEscapeHatch(){
        for(String s:List.of("fly","tp","home","back","command","creative","give","setblock","extension:travel"))assertFalse(VanillaPolicy.permitsAction(s));
        assertTrue(VanillaPolicy.permitsAction("respawn"));assertTrue(VanillaPolicy.permitsAction("craft"));
    }
}
