package world.earth35.world;

import java.util.*;
import world.earth35.world.State.Building;

/** The coastline, winding streets and mountain-to-sea river shared with the maps. */
public final class IslandGeography {
    private IslandGeography() {}
    public record Point(double x,double z) {}
    private static final double[][] OUTLINE={{-590,-150},{-555,-310},{-455,-390},{-430,-525},{-285,-580},{-145,-545},{-50,-610},{90,-570},{155,-455},{285,-415},{330,-300},{440,-240},{475,-90},{415,15},{460,130},{350,230},{320,370},{175,440},{60,395},{-45,460},{-170,425},{-245,320},{-380,310},{-415,195},{-535,130},{-500,5}};
    private static final double[][] RIVER_CONTROLS={{-205,-470},{-170,-423},{-80,-350},{-100,-295},{40,-242},{130,-165},{145,-76},{185,-5},{166,75},{233,145},{294,193},{380,230}};
    public static final double[][] STREETS={{-390,-240,90,-240},{90,-240,90,240},{90,240,-390,240},{-390,240,-390,-240},{-342,-240,-342,240},{-54,-240,-54,144},{42,-96,42,240},{-342,-192,90,-192},{-390,-96,90,-96},{-342,144,90,144},{-246,-192,-246,48},{-150,-96,-150,144},{-390,48,-150,48},{-150,0,42,0}};
    public static final List<Point> COAST=coast(),RIVER=smooth(RIVER_CONTROLS,false,16);
    public static final List<List<Point>> ROADS=Arrays.stream(STREETS).map(p->{
        int steps=(int)Math.ceil(Math.hypot(p[2]-p[0],p[3]-p[1])/4);List<Point> points=new ArrayList<>();
        for(int i=0;i<=steps;i++)points.add(survey(p[0]+(p[2]-p[0])*i/steps,p[1]+(p[3]-p[1])*i/steps));return List.copyOf(points);
    }).toList();
    public static Point survey(double x,double z) {
        double u=x+150;
        return new Point(-150+u*.82+18*Math.sin(z/76)+12*Math.sin((u+z)/110),z*.9+23*Math.sin(u/82)+12*Math.sin((u-z)/100));
    }
    public static Point inverse(double x,double z) {
        double u=(x+150)/.82,v=z/.9;
        for(int i=0;i<18;i++){u=(x+150-18*Math.sin(v/76)-12*Math.sin((u+v)/110))/.82;v=(z-23*Math.sin(u/82)-12*Math.sin((u-v)/100))/.9;}
        return new Point(u-150,v);
    }
    public static Traffic.Pose pose(Traffic.Pose p) {
        // Keep lane width in physical units while the street centerline bends.
        double qx=p.x(),qz=p.z(),nearest=Double.POSITIVE_INFINITY;
        for(double[] street:STREETS) {
            if((Math.abs(Math.sin(p.heading()))>.5)!=(street[0]!=street[2]))continue;
            double dx=street[2]-street[0],dz=street[3]-street[1];
            double t=Math.max(0,Math.min(1,((p.x()-street[0])*dx+(p.z()-street[1])*dz)/(dx*dx+dz*dz)));
            double x=street[0]+t*dx,z=street[1]+t*dz,d=Math.hypot(p.x()-x,p.z()-z);
            if(d<nearest){nearest=d;qx=x;qz=z;}
        }
        Point a=survey(qx,qz),b=survey(qx+Math.sin(p.heading())*.01,qz+Math.cos(p.heading())*.01);
        double heading=Math.atan2(b.x-a.x,b.z-a.z);
        double lateral=(p.x()-qx)*Math.cos(p.heading())-(p.z()-qz)*Math.sin(p.heading());
        return new Traffic.Pose(a.x+Math.cos(heading)*lateral,a.z-Math.sin(heading)*lateral,heading);
    }
    private static List<Point> smooth(double[][] controls,boolean closed,int samples) {
        List<Point> result=new ArrayList<>();int count=controls.length;
        for(int i=0;i<(closed?count:count-1);i++)for(int j=0;j<samples;j++) {
            double[] a=controls[closed?(i-1+count)%count:Math.max(0,i-1)],p=controls[i],b=controls[(i+1)%count],c=controls[closed?(i+2)%count:Math.min(count-1,i+2)];
            double t=(double)j/samples,t2=t*t,t3=t2*t;double[] out=new double[2];
            for(int k=0;k<2;k++)out[k]=.5*(2*p[k]+(-a[k]+b[k])*t+(2*a[k]-5*p[k]+4*b[k]-c[k])*t2+(-a[k]+3*p[k]-3*b[k]+c[k])*t3);
            result.add(new Point(out[0],out[1]));
        }
        if(!closed)result.add(new Point(controls[count-1][0],controls[count-1][1]));return List.copyOf(result);
    }
    private static List<Point> coast() {
        List<Point> points=smooth(OUTLINE,true,8);double area=0;
        for(int i=0;i<points.size();i++){Point a=points.get(i),b=points.get((i+1)%points.size());area+=a.x*b.z-b.x*a.z;}
        double scale=Math.sqrt(1e6/(Math.abs(area)/2));return points.stream().map(p->new Point(p.x*scale-170,p.z*scale-100)).toList();
    }
    public static boolean onIsland(double x,double z) {
        if(!Double.isFinite(x)||!Double.isFinite(z))return false;boolean inside=false;
        for(int i=0,j=COAST.size()-1;i<COAST.size();j=i++) {
            Point a=COAST.get(i),b=COAST.get(j);
            if((a.z>z)!=(b.z>z)&&x<(b.x-a.x)*(z-a.z)/(b.z-a.z)+a.x)inside=!inside;
        }
        return inside;
    }
    private static double segmentDistanceSquared(double x,double z,Point a,Point b) {
        double dx=b.x-a.x,dz=b.z-a.z,t=Math.max(0,Math.min(1,((x-a.x)*dx+(z-a.z)*dz)/(dx*dx+dz*dz)));
        double px=x-a.x-t*dx,pz=z-a.z-t*dz;return px*px+pz*pz;
    }
    public static double roadDistance(double x,double z) {
        double distance=Double.POSITIVE_INFINITY;
        for(var points:ROADS)for(int i=1;i<points.size();i++)distance=Math.min(distance,segmentDistanceSquared(x,z,points.get(i-1),points.get(i)));
        return Math.sqrt(distance);
    }
    public static boolean dryLand(double x,double z) {
        if(!onIsland(x,z)||!IslandLandscapes.clear(x,z))return false;
        if(IslandLandscapes.bridgeAt(x,z)!=null)return true;
        for(int i=1;i<RIVER.size();i++) {
            Point a=RIVER.get(i-1),b=RIVER.get(i);double radius=1.45+1.6*(double)i/(RIVER.size()-1);
            if(x<Math.min(a.x,b.x)-radius||x>Math.max(a.x,b.x)+radius||z<Math.min(a.z,b.z)-radius||z>Math.max(a.z,b.z)+radius)continue;
            double dx=b.x-a.x,dz=b.z-a.z,t=Math.max(0,Math.min(1,((x-a.x)*dx+(z-a.z)*dz)/(dx*dx+dz*dz)));
            radius=1.45+1.6*(i-1+t)/(RIVER.size()-1);
            if(segmentDistanceSquared(x,z,a,b)<radius*radius)return false;
        }
        return true;
    }
    public static Building coordinate(String id) {
        if(id==null||!id.startsWith("point:"))return null;
        try {
            String[] axes=id.substring(6).split(",",-1);if(axes.length!=2)return null;
            double x=Double.parseDouble(axes[0]),z=Double.parseDouble(axes[1]);if(!dryLand(x,z))return null;
            Building b=new Building(id,"Island landscape ("+x+", "+z+")","wilderness","Wilderness",x,z,100);
            b.restAllowed=true;return b;
        }catch(NumberFormatException invalid){return null;}
    }
    public record Exploration(String targetFormat,String boundary,List<Point> coastline,List<String> examples) {}
    public static Exploration exploration() {
        return new Exploration("MOVE or RUN target point:x,z","Dry island land, including connected mountain, forest, valley and coastal trails. Cross Silverstream on the three marked footbridges. Cloudmere Lake, waterfall cliffs and the exposed Basalt coastal cliff edge are inaccessible; approach their safe dry viewpoints.",COAST,
            List.of("point:-225,-450","point:-595,-100","point:-150,350","point:-172,-399","point:-65,-350","point:-705,-120","point:-710,10","point:-550,-245","point:204,134"));
    }
}
