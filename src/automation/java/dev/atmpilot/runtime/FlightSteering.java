package dev.atmpilot.runtime;

/** Flight key decisions, isolated from the client so inertia and camera behavior can be replayed. */
public final class FlightSteering {
    private static final int[] KEYS={0,1,-1};
    public record Controls(float yaw, int forward, int sideways, boolean sprint) {}
    private boolean initialized;
    private boolean routeHeading;
    private float heading;
    private double maxYawStep;
    public void reset(){initialized=false;routeHeading=false;}
    public double maxYawStep(){return maxYawStep;}
    private static double clamp(double value,double low,double high){return Math.max(low,Math.min(high,value));}
    private static double turn(double from,double to){return ((to-from+540)%360+360)%360-180;}
    public Controls horizontal(double dx,double dz,double vx,double vz,float yaw,boolean fast,double speed) {
        if(!Double.isFinite(dx+dz+vx+vz+yaw+speed)||speed<=0||speed>1)throw new IllegalArgumentException("Invalid flight sample");
        double distance=Math.hypot(dx,dz);
        if(!initialized){heading=yaw;initialized=true;}
        // The route controls the view. Velocity corrections never change the heading,
        // and the heading freezes near arrival even if the player crosses the center.
        if(!routeHeading&&distance>1.5){heading=(float)Math.toDegrees(Math.atan2(-dx,dz));routeHeading=true;}
        double step=clamp(turn(yaw,heading),-12,12);float view=(float)(yaw+step);
        maxYawStep=Math.max(maxYawStep,Math.abs(step));
        double angle=Math.toRadians(view),sin=Math.sin(angle),cos=Math.cos(angle);
        // Once braking has reached the arrival envelope, let drag settle instead
        // of firing another full key pulse to chase the exact center.
        double coast=1+.91+.91*.91;
        if(distance<.12&&Math.hypot(vx,vz)<.045&&Math.hypot(dx-vx*coast,dz-vz*coast)<.18)
            return new Controls(view,0,0,false);
        if(distance<3&&speed>.075){
            double best=Double.POSITIVE_INFINITY;int forward=0,sideways=0;
            for(int fi:KEYS)for(int si:KEYS){
                double norm=Math.max(1,Math.hypot(fi,si)),a=speed*.98/norm;
                double nx=vx+(-fi*sin+si*cos)*a,nz=vz+(fi*cos+si*sin)*a;
                double loss=nearCost(dx-nx,dz-nz,nx*.91,nz*.91,sin,cos,speed,2)+.003*(fi*fi+si*si)*speed*speed;
                if(loss<best-1e-12){best=loss;forward=fi;sideways=si;}
            }
            return new Controls(view,forward,sideways,false);
        }
        boolean sprintAllowed=fast&&distance>14&&Math.abs(turn(view,heading))<25;
        double desired=Math.min(speed*(sprintAllowed?20:10),distance*.28);
        double tx=distance>1e-6?dx/distance*desired:0,tz=distance>1e-6?dz/distance*desired:0;
        double best=Double.POSITIVE_INFINITY;int forward=0,sideways=0;boolean sprint=false;
        // Choose among the nine actual WASD combinations. The nearest achievable
        // acceleration supplies a natural dead band instead of full-speed oscillation.
        for(int f=0;f<3;f++)for(int s=0;s<3;s++){
            int fi=f==2?-1:f,si=s==2?-1:s;boolean run=sprintAllowed&&fi>0;
            double norm=Math.max(1,Math.hypot(fi,si)),acceleration=speed*.98*(run?2:1)/norm;
            double ax=(-fi*sin+si*cos)*acceleration,az=(fi*cos+si*sin)*acceleration;
            double loss=Math.pow(tx-vx-ax,2)+Math.pow(tz-vz-az,2);
            if(loss<best-1e-12){best=loss;forward=fi;sideways=si;sprint=run;}
        }
        return new Controls(view,forward,sideways,sprint);
    }
    private static double nearCost(double dx,double dz,double vx,double vz,double sin,double cos,double speed,int depth){
        if(depth==0)return dx*dx+dz*dz+18*(vx*vx+vz*vz);
        double best=Double.POSITIVE_INFINITY;
        for(int fi:KEYS)for(int si:KEYS){
            double norm=Math.max(1,Math.hypot(fi,si)),a=speed*.98/norm;
            double nx=vx+(-fi*sin+si*cos)*a,nz=vz+(fi*cos+si*sin)*a;
            best=Math.min(best,nearCost(dx-nx,dz-nz,nx*.91,nz*.91,sin,cos,speed,depth-1)+.003*(fi*fi+si*si)*speed*speed);
        }
        return best+.03*(dx*dx+dz*dz);
    }
    public static int vertical(double error,double velocity,double speed,boolean allowRise){
        double desired=clamp(error*.4,-speed*6,speed*6),best=Double.POSITIVE_INFINITY;int key=0;
        for(int candidate:new int[]{0,-1,1}){
            if(candidate>0&&!allowRise)continue;
            double loss=Math.abs(desired-velocity-candidate*speed*3);
            if(loss<best-1e-12){best=loss;key=candidate;}
        }
        return key;
    }
}
