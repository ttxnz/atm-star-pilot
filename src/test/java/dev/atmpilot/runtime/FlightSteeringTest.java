package dev.atmpilot.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightSteeringTest {
    private static double turn(double from,double to){return ((to-from+540)%360+360)%360-180;}

    @Test void brakingUsesReverseKeysWithoutTurningTheCameraAround(){
        var steering=new FlightSteering();
        var controls=steering.horizontal(0,.15,0,.09,0,false,.05);
        assertTrue(Math.abs(turn(0,controls.yaw()))<=12,"Camera must keep its travel heading while braking");
        assertEquals(-1,controls.forward(),"Brake using the normal backward key");
    }

    @Test void sidewaysCorrectionDoesNotSwingTheViewAwayFromTheRoute(){
        var steering=new FlightSteering();
        var controls=steering.horizontal(.02,.01,.09,0,0,false,.05);
        assertTrue(Math.abs(turn(0,controls.yaw()))<=12);
        assertEquals(-1,controls.sideways(),"Right key counters eastward drift at yaw zero");
    }

    @Test void routeTurnsUseTheShortestArcAcrossTheYawBoundary(){
        var steering=new FlightSteering();double a=Math.toRadians(-179);
        var controls=steering.horizontal(-Math.sin(a)*20,Math.cos(a)*20,0,0,179,true,.05);
        assertEquals(2,turn(179,controls.yaw()),.001);
    }
    @Test void overshootingTheDestinationDoesNotTurnTheCameraBackAround(){
        var steering=new FlightSteering();float yaw=0;
        yaw=steering.horizontal(0,20,0,0,yaw,true,.05).yaw();
        for(int tick=0;tick<40;tick++){
            var c=steering.horizontal(0,-5,0,.1,yaw,false,.05);
            assertEquals(0,turn(yaw,c.yaw()),.001,"Braking and returning must use keys, not rotate the route view");yaw=c.yaw();
        }
    }

    @Test void cardinalDiagonalAndShortFlightsConvergeWithoutCameraFlips(){
        for(double speed:new double[]{.05,.1})for(double distance:new double[]{.5,3,20,60})for(int angle=0;angle<360;angle+=45){
            var steering=new FlightSteering();double a=Math.toRadians(angle),x=0,z=0,vx=0,vz=0;
            double targetX=Math.sin(a)*distance,targetZ=Math.cos(a)*distance;float yaw=179;int stable=0,ticks=0;
            for(;ticks<240;ticks++){
                var c=steering.horizontal(targetX-x,targetZ-z,vx,vz,yaw,true,speed);
                assertTrue(Math.abs(turn(yaw,c.yaw()))<=12.001,"Unbounded camera turn");yaw=c.yaw();
                double r=Math.toRadians(yaw),norm=Math.max(1,Math.hypot(c.forward(),c.sideways()));
                double acc=speed*.98*(c.sprint()?2:1)/norm;
                vx+=(-c.forward()*Math.sin(r)+c.sideways()*Math.cos(r))*acc;
                vz+=(c.forward()*Math.cos(r)+c.sideways()*Math.sin(r))*acc;
                x+=vx;z+=vz;vx*=.91;vz*=.91;
                if(Math.hypot(targetX-x,targetZ-z)<.18&&Math.hypot(vx,vz)<.045){if(++stable>=3)break;}else stable=0;
            }
            assertTrue(ticks<240,"Failed arrival at speed="+speed+", distance="+distance+", angle="+angle+", error="+Math.hypot(targetX-x,targetZ-z));
        }
    }

    @Test void altitudeConvergesWithoutRapidDoubleTappingSpace(){
        for(double target:new double[]{-.5,.5,-6,6}){
            double y=0,vy=0;int lastPress=-100,timer=0,stable=0;boolean held=false;
            for(int tick=0;tick<120;tick++){
                if(timer>0)timer--;
                int key=FlightSteering.vertical(target-y,vy,.05,held||(timer==0&&tick-lastPress>=8));
                if(key>0&&!held){assertTrue(tick-lastPress>=8);lastPress=tick;timer=7;}
                held=key>0;vy+=key*.15;y+=vy;vy*=.6;
                if(Math.abs(target-y)<.25&&Math.abs(vy)<.035){if(++stable>=3)break;}else stable=0;
            }
            assertTrue(stable>=3,"Altitude did not settle, error="+(target-y));
        }
    }
}
