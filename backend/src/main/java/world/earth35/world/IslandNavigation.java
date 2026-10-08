package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;
import world.earth35.world.IslandGeography.Point;

/** Connected dry-land navigation, independent of city plot or street boundaries. */
final class IslandNavigation {
    private static final int STEP=8;
    private static final int MIN_X=-900,MIN_Z=-900,COLS=190,ROWS=190;
    private record MapKey(int geographyVersion,List<String> buildings) {}
    private record Visit(int id,double score) {}
    private record Connection(Point point,boolean incoming) {}
    private static MapKey cachedKey;
    private static Mesh cached;
    private static class Mesh {
        final List<Building> buildings;
        final List<Building> objects;
        final boolean terrain;
        final Map<String,List<double[]>> routes=Collections.synchronizedMap(new LinkedHashMap<>(256,.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String,List<double[]>> eldest){return size()>2048;}
        });
        final Map<Connection,Map<Integer,Double>> connections=Collections.synchronizedMap(new LinkedHashMap<>(128,.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Connection,Map<Integer,Double>> eldest){return size()>2048;}
        });
        // Calculate only edges visited by a route query; shared heights make reverse edges cheap.
        final float[] effort=new float[COLS*ROWS*8];
        final Map<Point,Double> elevations=Collections.synchronizedMap(new LinkedHashMap<>(4096,.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Point,Double> eldest){return size()>180000;}
        });
        final boolean[] walkable=new boolean[COLS*ROWS];
        final byte[] edges=new byte[COLS*ROWS];
        Mesh(State s) {
            terrain=s.geographyVersion>=4;
            buildings=s.buildings.values().stream().filter(Places::enclosed).toList();
            objects=WorldObjects.obstacles(s);
            for(int id=0;id<walkable.length;id++){Point p=point(id);walkable[id]=clearPoint(p.x(),p.z());}
            for(int id=0;id<walkable.length;id++)if(walkable[id])for(int dir=0;dir<8;dir++) {
                int next=neighbor(id,dir);if(next>=0&&walkable[next]&&clear(point(id),point(next)))edges[id]|=(byte)(1<<dir);
            }
        }
        boolean clearPoint(double x,double z) {
            return IslandGeography.dryLand(x,z)&&buildings.stream().noneMatch(b->Math.abs(x-b.x)<3.5&&Math.abs(z-b.z)<3.5)
                &&objects.stream().noneMatch(b->Math.abs(x-b.x)<WorldObjects.OBSTACLE_RADIUS&&Math.abs(z-b.z)<WorldObjects.OBSTACLE_RADIUS);
        }
        boolean clear(Point a,Point b) {
            for(Building building:buildings)if(intersects(a,b,building,3.5))return false;
            for(Building object:objects)if(intersects(a,b,object,WorldObjects.OBSTACLE_RADIUS))return false;
            int steps=(int)Math.ceil(Math.hypot(b.x()-a.x(),b.z()-a.z())/1.5);
            for(int i=0;i<=steps;i++){double t=steps==0?0:(double)i/steps;if(!IslandGeography.dryLand(a.x()+(b.x()-a.x())*t,a.z()+(b.z()-a.z())*t))return false;}
            return true;
        }
        double cost(Point a,Point b) {
            double distance=Math.hypot(b.x()-a.x(),b.z()-a.z());
            if(!terrain||distance<1e-10||TerrainElevation.flatSegment(a.x(),a.z(),b.x(),b.z()))return distance;
            // The search's short edges sample each two units. Final route facts,
            // shortcut comparisons and movement retain half-unit integration.
            int count=Math.max(1,(int)Math.ceil(distance/2));double step=distance/count;
            double previous=elevation(a),sum=0;
            for(int i=1;i<=count;i++) {
                Point sample=new Point(a.x()+(b.x()-a.x())*i/count,a.z()+(b.z()-a.z())*i/count);
                double y=elevation(sample);sum+=step*TerrainElevation.effortMultiplier((y-previous)/step);previous=y;
            }
            return sum;
        }
        private double elevation(Point p) {return elevations.computeIfAbsent(p,key->TerrainElevation.walkElevation(key.x(),key.z()));}
        double edgeCost(int id,int dir) {
            int slot=id*8+dir;double value=effort[slot];if(value==0)effort[slot]=(float)(value=cost(point(id),point(neighbor(id,dir))));return value;
        }
    }
    static boolean intersects(Point a,Point b,Building building,double radius) {
        if(Math.min(a.x(),b.x())>=building.x+radius||Math.max(a.x(),b.x())<=building.x-radius
            ||Math.min(a.z(),b.z())>=building.z+radius||Math.max(a.z(),b.z())<=building.z-radius)return false;
        double enter=0,exit=1;
        for(int i=0;i<2;i++) {
            double start=i==0?a.x():a.z(),delta=(i==0?b.x():b.z())-start,center=i==0?building.x:building.z;
            if(Math.abs(delta)<1e-10){if(Math.abs(start-center)>=radius)return false;}
            else {double first=(center-radius-start)/delta,last=(center+radius-start)/delta;enter=Math.max(enter,Math.min(first,last));exit=Math.min(exit,Math.max(first,last));if(enter>=exit-1e-8)return false;}
        }
        return exit>0&&enter<1;
    }
    private static synchronized Mesh mesh(State s) {
        List<String> features=new ArrayList<>(s.buildings.values().stream().filter(Places::enclosed).map(b->b.id+":"+b.x+":"+b.z).toList());
        features.addAll(s.worldObjects.values().stream().map(o->o.id+":"+o.x+":"+o.z).toList());
        MapKey key=new MapKey(s.geographyVersion,features);
        if(!key.equals(cachedKey)){cached=new Mesh(s);cachedKey=key;}return cached;
    }
    private static Point point(int id){double z=MIN_Z+(id/COLS)*STEP;if(z==-324)z=-322;if(z==68)z=70;if(z==-172)z=-170;return new Point(MIN_X+(id%COLS)*STEP,z);}
    private static final int[] DX={1,1,0,-1,-1,-1,0,1},DZ={0,1,1,1,0,-1,-1,-1};
    private static int neighbor(int id,int dir) {
        int x=id%COLS+DX[dir],z=id/COLS+DZ[dir];return x<0||x>=COLS||z<0||z>=ROWS?-1:z*COLS+x;
    }
    private static Map<Integer,Double> connectors(Mesh mesh,Point p,boolean incoming) {
        Connection key=new Connection(p,incoming);Map<Integer,Double> cached=mesh.connections.get(key);if(cached!=null)return cached;
        Map<Integer,Double> result=new HashMap<>();int cx=(int)Math.round((p.x()-MIN_X)/STEP),cz=(int)Math.round((p.z()-MIN_Z)/STEP);
        for(int dz=-4;dz<=4;dz++)for(int dx=-4;dx<=4;dx++) {
            int x=cx+dx,z=cz+dz;if(x<0||z<0||x>=COLS||z>=ROWS)continue;int id=z*COLS+x;
            if(mesh.walkable[id]&&mesh.clear(p,point(id)))result.put(id,incoming?mesh.cost(point(id),p):mesh.cost(p,point(id)));
        }
        Map<Integer,Double> connections=Map.copyOf(result);mesh.connections.put(key,connections);return connections;
    }
    static List<double[]> path(State s,Resident resident,Building destination) {
        List<double[]> result=new ArrayList<>();Point start=new Point(resident.x,resident.z);
        Building source=s.buildings.get(resident.location);
        if(source==null)source=s.buildings.values().stream().filter(b->Places.enclosed(b)&&Math.abs(resident.x-b.x)<3.35&&Math.abs(resident.z-b.z)<3.35).findFirst().orElse(null);
        if(Places.enclosed(source)) {
            var door=Places.entrance(source);result.add(new double[]{door.x(),resident.z});result.add(new double[]{door.x(),door.z()});start=new Point(door.x(),door.z());
        }
        var entrance=Places.entrance(destination);Point goal=new Point(entrance.x(),entrance.z());
        if(Math.hypot(start.x()-goal.x(),start.z()-goal.z())<.001)return result;
        Mesh mesh=mesh(s);
        String routeKey=start.x()+":"+start.z()+":"+goal.x()+":"+goal.z();
        List<double[]> cachedRoute=mesh.routes.get(routeKey);
        if(cachedRoute!=null){result.addAll(cachedRoute);return result;}
        boolean directClear=mesh.clear(start,goal);double directCost=directClear?exactCost(mesh,start,goal):Double.POSITIVE_INFINITY;
        // A clear flat route is optimal. On hills, compare it with the mesh rather
        // than allowing the former line-of-sight fast path to bypass terrain cost.
        double directDistance=Math.hypot(goal.x()-start.x(),goal.z()-start.z());
        if(directClear&&(!mesh.terrain||Math.abs(directCost-directDistance)<1e-7)) {
            var direct=List.of(new double[]{goal.x(),goal.z()});mesh.routes.put(routeKey,direct);result.addAll(direct);return result;
        }
        int exitPoints=result.size();
        Point outsideStart=start;
        Map<Integer,Double> origins=connectors(mesh,start,false),targets=connectors(mesh,goal,true);
        double[] cost=new double[COLS*ROWS];Arrays.fill(cost,Double.POSITIVE_INFINITY);int[] parent=new int[cost.length];Arrays.fill(parent,-1);
        PriorityQueue<Visit> queue=new PriorityQueue<>(Comparator.comparingDouble(Visit::score));
        double minimum=mesh.terrain?TerrainElevation.MIN_EFFORT_MULTIPLIER:1;
        for(var entry:origins.entrySet()){cost[entry.getKey()]=entry.getValue();Point p=point(entry.getKey());queue.add(new Visit(entry.getKey(),entry.getValue()+minimum*Math.hypot(goal.x()-p.x(),goal.z()-p.z())));}
        int last=-1;double best=directCost;boolean[] visited=new boolean[cost.length];
        while(!queue.isEmpty()) {
            Visit visit=queue.remove();if(visit.score>best)break;int id=visit.id;if(visited[id])continue;visited[id]=true;
            if(targets.containsKey(id)&&cost[id]+targets.get(id)<best){last=id;best=cost[id]+targets.get(id);}
            for(int dir=0;dir<8;dir++)if((mesh.edges[id]&(1<<dir))!=0) {
                int next=neighbor(id,dir);double c=cost[id]+mesh.edgeCost(id,dir);
                if(c<cost[next]){cost[next]=c;parent[next]=id;Point p=point(next);queue.add(new Visit(next,c+minimum*Math.hypot(goal.x()-p.x(),goal.z()-p.z())));}
            }
        }
        if(last<0&&directClear){var direct=List.of(new double[]{goal.x(),goal.z()});mesh.routes.put(routeKey,direct);result.addAll(direct);return result;}
        Engine.require(last>=0,"No dry-land route to this point; choose nearby land or a river footbridge");
        List<Point> route=new ArrayList<>();route.add(goal);for(int id=last;id>=0;id=parent[id])route.add(point(id));Collections.reverse(route);
        // Shortcuts must be both dry and no more expensive than the route they
        // replace. Visibility alone would restore a costly straight climb.
        for(int i=0;i<route.size();) {
            int next=i;double original=exactCost(mesh,start,route.get(i));
            for(int j=i+1;j<route.size();j++) {
                original+=exactCost(mesh,route.get(j-1),route.get(j));
                if(!mesh.clear(start,route.get(j)))break;
                if(!mesh.terrain||exactCost(mesh,start,route.get(j))<=original+1e-6)next=j;
            }
            Point p=route.get(next);result.add(new double[]{p.x(),p.z()});start=p;i=next+1;
        }
        // Search edges are deliberately cheaper to sample. Compare its final
        // route at full precision before preferring it to a clear direct route.
        if(directClear) {
            Point previous=outsideStart;double finalCost=0;
            for(int i=exitPoints;i<result.size();i++) {double[] p=result.get(i);Point current=new Point(p[0],p[1]);finalCost+=exactCost(mesh,previous,current);previous=current;}
            if(finalCost>directCost+1e-6){result.subList(exitPoints,result.size()).clear();result.add(new double[]{goal.x(),goal.z()});}
        }
        mesh.routes.put(routeKey,List.copyOf(result.subList(exitPoints,result.size())));return result;
    }
    private static double exactCost(Mesh mesh,Point a,Point b) {
        return mesh.terrain?TerrainElevation.segmentEffort(a.x(),a.z(),b.x(),b.z()):Math.hypot(b.x()-a.x(),b.z()-a.z());
    }
}
