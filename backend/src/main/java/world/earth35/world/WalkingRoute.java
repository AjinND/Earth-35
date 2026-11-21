package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** A stroll has a continuous itinerary through its grounds, rather than unrelated nearby stops. */
public final class WalkingRoute {
    private WalkingRoute() {}
    public record Plan(double centerX,double centerZ,double radius,List<double[]> points,List<double[]> approach) {}
    private record Visit(int id,double score) {}
    private record Footprint(double x,double z,double halfX,double halfZ,boolean circle) {
        double outside(double px,double pz) {
            return circle?Math.hypot(px-x,pz-z)/halfX:Math.max(Math.abs(px-x)/halfX,Math.abs(pz-z)/halfZ);
        }
        boolean intersects(double ax,double az,double bx,double bz) {
            if(circle) {
                double dx=bx-ax,dz=bz-az,length=dx*dx+dz*dz;
                double fraction=length==0?0:Math.max(0,Math.min(1,((x-ax)*dx+(z-az)*dz)/length));
                return Math.hypot(ax+fraction*dx-x,az+fraction*dz-z)<halfX-1e-7;
            }
            double low=0,high=1;
            for(double[] axis:new double[][]{{ax-x,bx-ax,halfX},{az-z,bz-az,halfZ}}) {
                if(Math.abs(axis[1])<1e-12){if(Math.abs(axis[0])>=axis[2]-1e-7)return false;continue;}
                double a=(-axis[2]+1e-7-axis[0])/axis[1],b=(axis[2]-1e-7-axis[0])/axis[1];
                low=Math.max(low,Math.min(a,b));high=Math.min(high,Math.max(a,b));if(low>=high)return false;
            }
            return low<high;
        }
    }

    public static Plan plan(State state,Resident resident,String location,int minutes) {
        Building place=Travel.destination(state,location);
        double cx=place==null?resident.x:place.x,cz=place==null?resident.z:place.z;
        double radius=place!=null&&place.outdoorRadius>0?Math.max(dimensions(place)[0],dimensions(place)[1]):state.geographyVersion>=2?18:4;
        Ground ground=new Ground(state,location,cx,cz,radius);
        if(!ground.base(resident.x,resident.z))return new Plan(cx,cz,radius,List.of(),List.of());
        // Duration affects the extent of a short outing; even a short walk uses
        // the place's grounds instead of tracing a tiny circle around arrival.
        double reach=Math.min(.76,Math.max(.5,CityLayout.of(state).walkingSpeed()*Math.max(1,minutes)/(2*Math.PI*Math.max(ground.rx,ground.rz))));
        int salt=Objects.hash(resident.id,location);double rotation=Math.floorMod(salt,24)*Math.PI/12;
        int direction=(salt&1)==0?1:-1;
        List<double[]> anchors=new ArrayList<>();
        for(int i=0;i<12;i++) {
            double angle=rotation+direction*i*Math.PI/6;
            // A short duration must not trap every candidate inside a central
            // fountain or other furniture. Try the outer grounds as well when
            // the preferred shorter circuit has no safe point in this sector.
            for(double factor:new double[]{reach,reach*.82,reach*.65,.26,.76,.86}) {
                double[] point={cx+ground.rx*factor*Math.cos(angle),cz+ground.rz*factor*Math.sin(angle)};
                if(ground.allowed(point[0],point[1])) {anchors.add(point);break;}
            }
        }
        if(anchors.size()<3)return new Plan(cx,cz,radius,List.of(),List.of());
        // Enter once, at the nearest reachable part of the circuit. The access
        // leg is kept separate so subsequent laps never return to arrival.
        int entry=-1;double entryCost=Double.POSITIVE_INFINITY;List<double[]> approach=List.of();
        List<Integer> candidates=new ArrayList<>();for(int i=0;i<anchors.size();i++)candidates.add(i);
        candidates.sort(Comparator.comparingDouble(i->Math.hypot(anchors.get(i)[0]-resident.x,anchors.get(i)[1]-resident.z)));
        for(int index:candidates) {
            double[] point=anchors.get(index);double direct=Math.hypot(point[0]-resident.x,point[1]-resident.z);
            if(direct>entryCost)break;
            List<double[]> access=ground.path(resident.x,resident.z,point);
            if(access.isEmpty()&&direct>1e-6)continue;
            double cost=length(resident.x,resident.z,access);
            if(cost<entryCost) {entry=index;entryCost=cost;approach=access;}
        }
        if(entry<0)return new Plan(cx,cz,radius,List.of(),List.of());
        List<double[]> circuit=new ArrayList<>();double[] first=anchors.get(entry);circuit.add(first.clone());
        for(int i=1;i<anchors.size();i++) {
            double[] previous=circuit.getLast(),next=anchors.get((entry+i)%anchors.size());
            List<double[]> leg=ground.path(previous[0],previous[1],next);
            // Streets or water can split a destination's nominal footprint.
            // Stay on the entered connected grounds instead of crossing them.
            if(leg.isEmpty()&&Math.hypot(next[0]-previous[0],next[1]-previous[1])>1e-6)continue;
            circuit.addAll(leg);
        }
        if(circuit.size()<3)return new Plan(cx,cz,radius,List.of(),List.of());
        double[] last=circuit.getLast();List<double[]> closing=ground.path(last[0],last[1],first);
        if(closing.isEmpty()&&!same(last,first))return new Plan(cx,cz,radius,List.of(),List.of());
        circuit.addAll(closing);
        // The cyclic list already closes last -> first; don't duplicate first.
        if(circuit.size()>1&&same(circuit.getFirst(),circuit.getLast()))circuit.removeLast();
        return new Plan(cx,cz,radius,circuit,approach);
    }

    public static boolean canStart(State s,String location,double cx,double cz,double radius,double x,double z) {
        return new Ground(s,location,cx,cz,radius).base(x,z);
    }
    public static boolean allowed(State s,String location,double cx,double cz,double radius,double x,double z) {
        return new Ground(s,location,cx,cz,radius).allowed(x,z);
    }
    /** Existing arrivals may overlap furniture. Permit physical egress only from that overlap. */
    public static boolean allowedFrom(State s,String location,double cx,double cz,double radius,double fromX,double fromZ,double x,double z) {
        return new Ground(s,location,cx,cz,radius).allowedFrom(fromX,fromZ,x,z);
    }
    public static boolean clearSegment(State s,String location,double cx,double cz,double radius,double x,double z,double nx,double nz) {
        return new Ground(s,location,cx,cz,radius).clear(x,z,nx,nz,false);
    }
    public static List<double[]> path(State s,String location,double cx,double cz,double radius,double fromX,double fromZ,double[] destination) {
        return new Ground(s,location,cx,cz,radius).path(fromX,fromZ,destination);
    }
    private static boolean same(double[] a,double[] b) {return Math.hypot(a[0]-b[0],a[1]-b[1])<1e-6;}
    private static double length(double x,double z,List<double[]> points) {
        double distance=0;for(double[] p:points){distance+=Math.hypot(p[0]-x,p[1]-z);x=p[0];z=p[1];}return distance;
    }
    private static double[] dimensions(Building place) {
        return OutdoorPlanting.dimensions(place);
    }
    private static class Ground {
        final State state;final double cx,cz,radius,rx,rz;final boolean expanded;
        final List<Building> buildings;final List<WorldObjects.PlacedObject> objects;
        final List<Footprint> furniture=new ArrayList<>();
        Ground(State state,String location,double cx,double cz,double radius) {
            this.state=state;this.cx=cx;this.cz=cz;this.radius=radius;
            Building place=Travel.destination(state,location);expanded=place!=null&&place.outdoorRadius>0;
            double[] dims=expanded?dimensions(place):new double[]{radius,radius};rx=dims[0];rz=dims[1];
            buildings=state.buildings.values().stream().filter(Places::enclosed).filter(b->Math.abs(b.x-cx)<radius+5&&Math.abs(b.z-cz)<radius+5).toList();
            objects=state.worldObjects.values().stream().filter(o->Math.abs(o.x-cx)<radius+3&&Math.abs(o.z-cz)<radius+3).toList();
            for(Building b:state.buildings.values()) {
                if(Math.abs(b.x-cx)>radius+b.outdoorRadius+8||Math.abs(b.z-cz)>radius+b.outdoorRadius+8)continue;
                if("park".equals(b.id)||"plaza".equals(b.type))furniture.add(new Footprint(b.x,b.z,2.55,2.55,true));
                if(b.outdoorRadius>0) {
                    if("hangout".equals(b.type))for(int side:new int[]{-1,1})for(int end:new int[]{-1,1})
                        furniture.add(new Footprint(b.x+side*4,b.z+end*3,.475,.475,false));
                    OutdoorPlanting.Layout planting=OutdoorPlanting.layout(state,b);
                    for(var seat:planting.benches())bench(b,seat.x(),seat.z(),seat.rotated());
                    for(var tree:planting.trees())furniture.add(new Footprint(tree.x(),tree.z(),tree.trunkRadius()+.35,tree.trunkRadius()+.35,true));
                    for(var bed:planting.beds())furniture.add(new Footprint(bed.x(),bed.z(),1.35+.35,.7+.35,false));
                } else {
                    for(double[] seat:Seating.benches(b))bench(b,seat[0],seat[1],Math.abs(Math.sin(seat[2]))>.5);
                    if("garden".equals(b.type))for(double x:new double[]{-2.7,0,2.7})furniture.add(new Footprint(b.x+x,b.z-2.8,1.35,1.5,false));
                    if("garden".equals(b.type)||"hangout".equals(b.type))furniture.add(new Footprint(b.x,b.z,2.1,1.25,false));
                    if("hangout".equals(b.type))for(double x:new double[]{-3.5,3.5})for(int z:new int[]{-3,3})furniture.add(new Footprint(b.x+x,b.z+z,.5,.5,false));
                }
            }
        }
        private void bench(Building b,double x,double z,boolean rotated) {
            // Mesh size 2.8 x .8, plus clearance for a walking resident.
            furniture.add(new Footprint(b.x+x,b.z+z,rotated?.75:1.75,rotated?1.75:.75,false));
        }
        boolean base(double x,double z) {
            if(!Double.isFinite(x)||!Double.isFinite(z)||radius<=0||Math.hypot(x-cx,z-cz)>radius+1e-7)return false;
            if(expanded&&Math.hypot((x-cx)/rx,(z-cz)/rz)>.9+1e-7)return false;
            if(state.geographyVersion>=2) {
                if(!IslandGeography.dryLand(x,z)||state.geographyVersion>=4&&!IslandLandscapes.clear(x,z)
                    ||IslandGeography.roadDistance(x,z)<(expanded?3.5:2.2))return false;
            } else {
                CityLayout layout=CityLayout.of(state);
                if(Math.abs(x-layout.nearestRoad(x,true))<2.2||Math.abs(z-layout.nearestRoad(z,false))<2.2)return false;
            }
            for(Building building:buildings)if(Math.abs(x-building.x)<(expanded?4.8:3.6)&&Math.abs(z-building.z)<(expanded?4.8:3.6))return false;
            for(var object:objects)if(Math.abs(x-object.x)<WorldObjects.OBSTACLE_RADIUS+.35&&Math.abs(z-object.z)<WorldObjects.OBSTACLE_RADIUS+.35)return false;
            return true;
        }
        boolean allowed(double x,double z) {
            return base(x,z)&&furniture.stream().noneMatch(f->f.outside(x,z)<1-1e-7);
        }
        boolean allowedFrom(double fromX,double fromZ,double x,double z) {
            if(!base(x,z))return false;
            for(var object:objects) {
                double radius=WorldObjects.OBSTACLE_RADIUS+.35;
                if(new Footprint(object.x,object.z,radius,radius,false).intersects(fromX,fromZ,x,z))return false;
            }
            for(Footprint f:furniture) {
                double source=f.outside(fromX,fromZ);
                if(source>=1-1e-7) {if(f.intersects(fromX,fromZ,x,z))return false;}
                else if(f.outside(x,z)<source-1e-7)return false;
            }
            return true;
        }
        boolean clear(double x,double z,double nx,double nz,boolean egress) {
            int samples=Math.max(1,(int)Math.ceil(Math.hypot(nx-x,nz-z)/.3));
            double px=x,pz=z;
            for(int i=0;i<=samples;i++) {
                double sx=x+(nx-x)*i/samples,sz=z+(nz-z)*i/samples;
                if(!allowedFrom(px,pz,sx,sz)||!egress&&!allowed(sx,sz))return false;
                px=sx;pz=sz;
            }
            return true;
        }
        List<double[]> path(double x,double z,double[] destination) {
            if(destination==null||destination.length<2||!base(x,z)||!allowed(destination[0],destination[1]))return new ArrayList<>();
            if(Math.hypot(x-destination[0],z-destination[1])<1e-6)return new ArrayList<>();
            if(clear(x,z,destination[0],destination[1],true))return new ArrayList<>(List.of(destination.clone()));
            // A small local mesh stays inside this destination. Global travel
            // routes can otherwise leave a park to cut along a city street.
            double step=Math.max(.5,Math.min(1.5,Math.min(rx,rz)/12));
            int cols=2*(int)Math.ceil(rx/step)+1,rows=2*(int)Math.ceil(rz/step)+1,count=cols*rows;
            double minX=cx-(cols-1)*step/2,minZ=cz-(rows-1)*step/2;
            boolean[] safe=new boolean[count];double[][] points=new double[count][];
            for(int id=0;id<count;id++){points[id]=new double[]{minX+(id%cols)*step,minZ+(id/cols)*step};safe[id]=allowed(points[id][0],points[id][1]);}
            double[] costs=new double[count];Arrays.fill(costs,Double.POSITIVE_INFINITY);
            int[] parents=new int[count];Arrays.fill(parents,-1);
            PriorityQueue<Visit> queue=new PriorityQueue<>(Comparator.comparingDouble(Visit::score).thenComparingInt(Visit::id));
            for(int id=0;id<count;id++)if(safe[id]&&Math.hypot(points[id][0]-x,points[id][1]-z)<=step*3
                &&clear(x,z,points[id][0],points[id][1],true)) {
                costs[id]=Math.hypot(points[id][0]-x,points[id][1]-z);queue.add(new Visit(id,costs[id]+distance(points[id],destination)));
            }
            int reached=-1;boolean[] visited=new boolean[count];
            int[] dx={1,1,0,-1,-1,-1,0,1},dz={0,1,1,1,0,-1,-1,-1};
            while(!queue.isEmpty()) {
                int id=queue.remove().id();if(visited[id])continue;visited[id]=true;
                if(distance(points[id],destination)<=step*3&&clear(points[id][0],points[id][1],destination[0],destination[1],false)){reached=id;break;}
                for(int direction=0;direction<8;direction++) {
                    int col=id%cols+dx[direction],row=id/cols+dz[direction];if(col<0||col>=cols||row<0||row>=rows)continue;
                    int next=row*cols+col;if(!safe[next]||visited[next])continue;
                    double cost=costs[id]+distance(points[id],points[next]);
                    if(cost>=costs[next]||!clear(points[id][0],points[id][1],points[next][0],points[next][1],false))continue;
                    costs[next]=cost;parents[next]=id;queue.add(new Visit(next,cost+distance(points[next],destination)));
                }
            }
            if(reached<0)return new ArrayList<>();
            List<double[]> mesh=new ArrayList<>();mesh.add(destination.clone());
            for(int id=reached;id>=0;id=parents[id])mesh.add(points[id]);Collections.reverse(mesh);
            List<double[]> result=new ArrayList<>();
            for(int i=0;i<mesh.size();) {
                int next=i;for(int j=i+1;j<mesh.size();j++)if(clear(x,z,mesh.get(j)[0],mesh.get(j)[1],result.isEmpty()))next=j;
                double[] point=mesh.get(next);if(Math.hypot(point[0]-x,point[1]-z)>1e-6)result.add(point);
                x=point[0];z=point[1];i=next+1;
            }
            return result;
        }
        private static double distance(double[] a,double[] b){return Math.hypot(a[0]-b[0],a[1]-b[1]);}
    }
}
