package dev.atmpilot.vanilla;

import java.util.Optional;

/** Pure geometry used by actual observed Eye-of-Ender paths and bow trajectories. */
public final class VanillaMath {
    private VanillaMath() {}
    public record Ray(double x, double z, double dx, double dz) {}
    public record Point(double x, double z) {}
    public static Optional<Point> intersect(Ray a, Ray b) {
        double al=Math.hypot(a.dx,a.dz), bl=Math.hypot(b.dx,b.dz);
        if(al<1||bl<1||Math.hypot(a.x-b.x,a.z-b.z)<16)return Optional.empty();
        double ax=a.dx/al, az=a.dz/al, bx=b.dx/bl, bz=b.dz/bl;
        double cross=ax*bz-az*bx;
        if(Math.abs(cross)<.03)return Optional.empty();
        double qx=b.x-a.x,qz=b.z-a.z;
        double t=(qx*bz-qz*bx)/cross,u=(qx*az-qz*ax)/cross;
        if(t<0||u<0||t>12000||u>12000)return Optional.empty();
        return Optional.of(new Point(a.x+t*ax,a.z+t*az));
    }
    /** Low arc, angle above horizontal; vanilla full-draw arrow speed and gravity. */
    public static Optional<Double> launchAngle(double horizontal,double vertical,double speed,double gravity) {
        if(horizontal<=.01||speed<=0||gravity<=0)return Optional.empty();
        double v2=speed*speed,d=v2*v2-gravity*(gravity*horizontal*horizontal+2*vertical*v2);
        if(d<0)return Optional.empty();
        double angle=Math.atan((v2-Math.sqrt(d))/(gravity*horizontal));
        return Double.isFinite(angle)?Optional.of(angle):Optional.empty();
    }
    /** Discrete vanilla arrow drag/gravity; find the first (low) launch arc. */
    public static Optional<Double> arrowAngle(double range,double height){
        if(range<.1||range>160||!Double.isFinite(height))return Optional.empty();
        double previous=-Math.toRadians(85), error=arrowHeight(range,previous)-height;
        for(int deg=-84;deg<=85;deg++){
            double angle=Math.toRadians(deg),next=arrowHeight(range,angle)-height;
            if(Double.isFinite(error)&&Double.isFinite(next)&&error<=0&&next>=0){
                double lo=previous,hi=angle;for(int i=0;i<25;i++){double mid=(lo+hi)/2;if(arrowHeight(range,mid)<height)lo=mid;else hi=mid;}
                return Optional.of((lo+hi)/2);
            }previous=angle;error=next;
        }return Optional.empty();
    }
    private static double arrowHeight(double range,double angle){
        double x=0,y=0,vx=3*Math.cos(angle),vy=3*Math.sin(angle);
        for(int i=0;i<160;i++){double nx=x+vx,ny=y+vy;if(nx>=range)return y+(ny-y)*(range-x)/(nx-x);x=nx;y=ny;vx*=.99;vy=vy*.99-.05;}
        return Double.NaN;
    }
    /** Deterministic expanding square search, never the same eight-step circle. */
    public static Point spiral(int index){
        if(index<0)throw new IllegalArgumentException("Negative search index");if(index==0)return new Point(0,0);
        int ring=(int)Math.ceil((Math.sqrt(index+1)-1)/2), side=2*ring, max=(2*ring+1)*(2*ring+1)-1, offset=max-index;
        if(offset<side)return new Point(ring-offset,-ring);
        if(offset<2*side)return new Point(-ring,-ring+offset-side);
        if(offset<3*side)return new Point(-ring+offset-2*side,ring);
        return new Point(ring,ring-(offset-3*side));
    }
}
