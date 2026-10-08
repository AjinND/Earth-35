package world.earth35.world;

import java.util.*;
import world.earth35.world.IslandGeography.Point;

/** Authoritative counterpart of web/lib/island.ts, in ten-metre map units. */
public final class TerrainElevation {
    private TerrainElevation() {}
    public static final double SAMPLE_STEP=.5;
    // The gentlest descent is the fastest possible segment. A* uses this lower bound.
    public static final double MIN_EFFORT_MULTIPLIER=1/(1+1.2*.08);
    private static final Map<Point,Double> WALK_HEIGHTS=Collections.synchronizedMap(new LinkedHashMap<>(4096,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Point,Double> eldest){return size()>180000;}
    });
    private record SegmentKey(double x,double z,double nx,double nz) {}
    private static final Map<SegmentKey,Segment> SEGMENTS=Collections.synchronizedMap(new LinkedHashMap<>(512,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<SegmentKey,Segment> eldest){return size()>8192;}
    });
    private record Edge(double x,double z,double dx,double dz,double inverseSquared,double minX,double maxX,double minZ,double maxZ) {}
    private static final Edge[] COAST_EDGES=edges(IslandGeography.COAST,true),RIVER_EDGES=edges(IslandGeography.RIVER,false);
    public record River(double distance,int index,double fraction,double width) {}
    public record Segment(double walkingEffort,double runningEnergyEffort,double maxGrade) {}
    private static Edge[] edges(List<Point> points,boolean closed) {
        Edge[] edges=new Edge[points.size()-(closed?0:1)];
        for(int i=0;i<edges.length;i++) {
            Point a=points.get(i),b=points.get((i+1)%points.size());double dx=b.x()-a.x(),dz=b.z()-a.z();
            edges[i]=new Edge(a.x(),a.z(),dx,dz,1/(dx*dx+dz*dz),Math.min(a.x(),b.x()),Math.max(a.x(),b.x()),Math.min(a.z(),b.z()),Math.max(a.z(),b.z()));
        }
        return edges;
    }
    public static double coastDistance(double x,double z) {
        return coastDistance(x,z,Double.POSITIVE_INFINITY);
    }
    private static double coastDistance(double x,double z,double limit) {
        double nearest=limit*limit;
        for(Edge edge:COAST_EDGES) {
            if(x<edge.minX-limit||x>edge.maxX+limit||z<edge.minZ-limit||z>edge.maxZ+limit)continue;
            double ex=x<edge.minX?edge.minX-x:x>edge.maxX?x-edge.maxX:0,ez=z<edge.minZ?edge.minZ-z:z>edge.maxZ?z-edge.maxZ:0;
            if(ex*ex+ez*ez>nearest)continue;
            double t=clamp(((x-edge.x)*edge.dx+(z-edge.z)*edge.dz)*edge.inverseSquared,0,1);
            double px=x-edge.x-edge.dx*t,pz=z-edge.z-edge.dz*t;nearest=Math.min(nearest,px*px+pz*pz);
        }
        return Math.sqrt(nearest);
    }
    private static double peak(double x,double z,double cx,double cz,double height,double rx,double rz) {
        return height*Math.exp(-Math.pow((x-cx)/rx,2)-Math.pow((z-cz)/rz,2));
    }
    public static double naturalElevation(double x,double z) {
        double settlement=Math.max(Math.max(Math.abs(x+150)-288,Math.abs(z)-288),0),fade=Math.min(1,settlement/75);
        if(fade==0)return 0;
        double relief=peak(x,z,-370,-390,76,130,120)+peak(x,z,-205,-465,96,105,120)+peak(x,z,-50,-420,55,120,85)
            +peak(x,z,-475,-180,30,150,190)+peak(x,z,-260,140,18,190,140);
        for(var feature:IslandLandscapes.SPEC.terrainRelief())
            relief+=peak(x,z,feature.x(),feature.z(),feature.height(),feature.radiusX(),feature.radiusZ());
        double ridges=1-clamp((relief-32)/28,0,1)*.22*(1-Math.abs(Math.sin((x+z*.45)*.04)));
        // Every coastal effect saturates by 55 units. Interior points need no
        // expensive projections onto any of the remote coastline segments.
        double coast=coastDistance(x,z,55),coastFade=Math.min(1,coast/55);
        for(var zone:IslandLandscapes.SPEC.coastalZones()) {
            double weight=zoneWeight(x,z,zone.x(),zone.z(),zone.radiusX(),zone.radiusZ());
            if(zone.kind().equals("cliff"))coastFade+=(Math.min(1,coast/6)-coastFade)*weight;
            else if(zone.kind().equals("rocky"))coastFade+=(Math.min(1,coast/18)-coastFade)*weight;
        }
        double ground=fade*Math.max(0,relief)*ridges*coastFade;
        for(var zone:IslandLandscapes.SPEC.coastalZones())if(zone.kind().equals("cove")) {
            double weight=zoneWeight(x,z,zone.x(),zone.z(),zone.radiusX(),zone.radiusZ())*(1-Math.min(1,coast/45));
            ground+=(coast*.045-ground)*weight;
        }
        return ground;
    }
    private static double zoneWeight(double x,double z,double cx,double cz,double rx,double rz) {return clamp((1.3-Math.hypot((x-cx)/rx,(z-cz)/rz))/.3,0,1);}
    private static final double[] RIVER_LEVELS=riverLevels();
    private static double[] riverLevels() {
        var spec=IslandLandscapes.SPEC;double[] levels=new double[IslandGeography.RIVER.size()];double previous=Double.POSITIVE_INFINITY;
        for(int i=0;i<levels.length;i++) {
            Point p=IslandGeography.RIVER.get(i);
            double natural=IslandGeography.onIsland(p.x(),p.z())?Math.max(.14,naturalElevation(p.x(),p.z())-.4):-.65;
            double level=i>=spec.lakeRiverStart()&&i<=spec.lakeRiverEnd()?spec.lake().level():natural;
            levels[i]=previous=Math.min(previous,level);
        }
        return levels;
    }
    public static double riverLevel(int index,double fraction) {
        double t=index==IslandLandscapes.SPEC.lakeRiverEnd()?clamp((fraction-.48)/.04,0,1):fraction;
        return RIVER_LEVELS[index]+(RIVER_LEVELS[Math.min(index+1,RIVER_LEVELS.length-1)]-RIVER_LEVELS[index])*t;
    }
    public static River nearestRiver(double x,double z) {
        double squared=Double.POSITIVE_INFINITY,fraction=0;int index=0;
        for(int i=0;i<RIVER_EDGES.length;i++) {
            Edge edge=RIVER_EDGES[i];
            // Distance to the segment's bounding rectangle cheaply excludes remote reaches.
            double ex=x<edge.minX?edge.minX-x:x>edge.maxX?x-edge.maxX:0,ez=z<edge.minZ?edge.minZ-z:z>edge.maxZ?z-edge.maxZ:0;
            if(ex*ex+ez*ez>squared)continue;
            double t=clamp(((x-edge.x)*edge.dx+(z-edge.z)*edge.dz)*edge.inverseSquared,0,1);
            double px=x-edge.x-t*edge.dx,pz=z-edge.z-t*edge.dz,d=px*px+pz*pz;
            if(d<squared){squared=d;index=i;fraction=t;}
        }
        return new River(Math.sqrt(squared),index,fraction,2.2+3.2*(index+fraction)/(IslandGeography.RIVER.size()-1));
    }
    public static double poolRadius(double x,double z) {
        Point lip=IslandGeography.RIVER.get(IslandLandscapes.SPEC.lakeRiverEnd()),foot=IslandGeography.RIVER.get(IslandLandscapes.SPEC.lakeRiverEnd()+1);
        double length=Math.hypot(foot.x()-lip.x(),foot.z()-lip.z()),ux=(foot.x()-lip.x())/length,uz=(foot.z()-lip.z())/length;
        double dx=x-(lip.x()+(foot.x()-lip.x())*.52),dz=z-(lip.z()+(foot.z()-lip.z())*.52);
        double along=(dx*ux+dz*uz-1.25)/2,across=(dx*uz-dz*ux)/4.2,angle=Math.atan2(across,along);
        return Math.hypot(along,across)/(1+.055*Math.sin(angle*3)+.035*Math.cos(angle*5));
    }
    public static double islandElevation(double x,double z) {
        var spec=IslandLandscapes.SPEC;double ground=naturalElevation(x,z);
        var lake=spec.lake();double radius=Math.abs(x-lake.x())<lake.radiusX()*1.8&&Math.abs(z-lake.z())<lake.radiusZ()*1.8?IslandLandscapes.lakeRadius(x,z):2;
        if(radius<1.55) {
            double bed=spec.lake().level()-1.8,rim=spec.lake().level()+.7;
            ground=radius<1?bed+(rim-bed)*Math.max(0,(radius-.88)/.12):rim+(ground-rim)*Math.min(1,(radius-1)/.55);
        }
        boolean nearRiver=false;
        for(Edge edge:RIVER_EDGES)if(x>=edge.minX-10&&x<=edge.maxX+10&&z>=edge.minZ-10&&z<=edge.maxZ+10){nearRiver=true;break;}
        if(!nearRiver)return ground;
        River r=nearestRiver(x,z);double position=r.index()+r.fraction(),level=riverLevel(r.index(),r.fraction());
        boolean inLake=radius<1&&level<=spec.lake().level()+.1;
        if(position<spec.cliffRiverEnd()+2&&!inLake&&r.distance()<r.width()/2+7) {
            double bank=level+.7,blend=clamp((r.distance()-r.width()/2)/7,0,1);ground=bank+(ground-bank)*blend;
        }
        double pool=poolRadius(x,z);Point lip=IslandGeography.RIVER.get(spec.lakeRiverEnd()),foot=IslandGeography.RIVER.get(spec.lakeRiverEnd()+1);
        double length=Math.hypot(foot.x()-lip.x(),foot.z()-lip.z());
        double downstream=(x-lip.x()-(foot.x()-lip.x())*.52)*(foot.x()-lip.x())/length+(z-lip.z()-(foot.z()-lip.z())*.52)*(foot.z()-lip.z())/length;
        if(pool<1.35&&downstream>=0) {
            double poolLevel=riverLevel(spec.lakeRiverEnd()+1,0),bed=poolLevel-1.25,rim=poolLevel+.3;
            ground=pool<1?bed+(rim-bed)*Math.max(0,(pool-.72)/.28):rim+(ground-rim)*Math.min(1,(pool-1)/.35);
        }
        if(r.distance()>r.width()/2+4)return ground;
        double channel=level-.6,carved=channel+(ground-channel)*clamp((r.distance()-r.width()/2)/4,0,1);
        return position<spec.cliffRiverEnd()+2&&!inLake&&pool>=1?carved:Math.min(ground,carved);
    }
    public static double walkElevation(double x,double z) {
        return WALK_HEIGHTS.computeIfAbsent(new Point(x,z),point->walkHeight(point.x(),point.z()));
    }
    private static double walkHeight(double x,double z) {
        for(var bridge:IslandLandscapes.SPEC.bridges())if(Math.abs(z-bridge.z())<=bridge.width()/2+1e-9) {
            double rampDistance=Math.max(0,Math.abs(x-bridge.x())-bridge.length()/2);
            if(rampDistance<4) {
                River r=nearestRiver(bridge.x(),bridge.z());double deck=riverLevel(r.index(),r.fraction())+.55;
                return deck+(islandElevation(x,z)-deck)*Math.min(1,rampDistance/4);
            }
        }
        return islandElevation(x,z);
    }
    public static double effortMultiplier(double grade) {
        return Math.hypot(1,grade)*(1+4*Math.max(grade,0)+3*Math.max(-grade-.08,0))/(1+1.2*Math.min(.08,Math.max(-grade,0)));
    }
    public static double energyMultiplier(double grade) {return 1+2.5*Math.max(grade,0)+.5*Math.max(-grade-.08,0);}
    /** A settlement segment whose entire rectangle is beyond every water bed and bridge ramp. */
    public static boolean flatSegment(double x,double z,double nx,double nz) {
        double minX=Math.min(x,nx),maxX=Math.max(x,nx),minZ=Math.min(z,nz),maxZ=Math.max(z,nz);
        if(minX< -438||maxX>138||minZ< -288||maxZ>288)return false;
        // In this rectangle the natural terrain is exactly zero, and the lake,
        // falls basin and raised highland banks are all outside it. Only the
        // ordinary channel (maximum half-width 2.7 + four-unit bank) remains.
        for(Edge edge:RIVER_EDGES)
            if(maxX>=edge.minX-6.7&&minX<=edge.maxX+6.7&&maxZ>=edge.minZ-6.7&&minZ<=edge.maxZ+6.7)return false;
        for(var bridge:IslandLandscapes.SPEC.bridges())
            if(maxX>=bridge.x()-bridge.length()/2-4-1e-9&&minX<=bridge.x()+bridge.length()/2+4+1e-9
                &&maxZ>=bridge.z()-bridge.width()/2-1e-9&&minZ<=bridge.z()+bridge.width()/2+1e-9)return false;
        return true;
    }
    /** Integrate terrain along the same half-unit steps used by the movement loop. */
    public static Segment segment(double x,double z,double nx,double nz) {
        if(flatSegment(x,z,nx,nz)){double distance=Math.hypot(nx-x,nz-z);return new Segment(distance,distance,0);}
        if(Math.hypot(nx-x,nz-z)<=1)return measureSegment(x,z,nx,nz);
        return SEGMENTS.computeIfAbsent(new SegmentKey(x,z,nx,nz),key->measureSegment(key.x(),key.z(),key.nx(),key.nz()));
    }
    private static Segment measureSegment(double x,double z,double nx,double nz) {
        double distance=Math.hypot(nx-x,nz-z);if(distance<1e-10)return new Segment(0,0,0);
        int samples=Math.max(1,(int)Math.ceil(distance/SAMPLE_STEP));double step=distance/samples,previous=walkElevation(x,z),effort=0,energy=0,steepest=0;
        for(int i=1;i<=samples;i++) {
            double y=walkElevation(x+(nx-x)*i/samples,z+(nz-z)*i/samples),grade=(y-previous)/step;
            double cost=step*effortMultiplier(grade);effort+=cost;energy+=cost*energyMultiplier(grade);steepest=Math.max(steepest,Math.abs(grade));previous=y;
        }
        return new Segment(effort,energy,steepest);
    }
    public static double segmentEffort(double x,double z,double nx,double nz) {return segment(x,z,nx,nz).walkingEffort();}
    public static double segmentRunningEnergy(double x,double z,double nx,double nz) {return segment(x,z,nx,nz).runningEnergyEffort();}
    public static double segmentMaxGrade(double x,double z,double nx,double nz) {return segment(x,z,nx,nz).maxGrade();}
    private static double clamp(double value,double min,double max) {return Math.min(max,Math.max(min,value));}
}
