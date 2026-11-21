package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Physical planting shared with outdoor-layout.ts, outdoor-planting.ts and OutdoorDestination. */
final class OutdoorPlanting {
    private OutdoorPlanting() {}
    static final double CELL_SIZE=2;
    record Tree(int id,String kind,double x,double z,double size,double radius) {
        double trunkRadius(){return (kind.equals("birch")?.16:kind.equals("pine")||kind.equals("alpine-pine")?.2:.3)*size;}
    }
    record Bed(double x,double z) {}
    record Bench(double x,double z,double rotation) {
        boolean rotated(){return Math.abs(Math.sin(rotation))>.5;}
    }
    private record Bounds(double x,double z,double halfX,double halfZ) {
        boolean overlaps(Bounds other,double gap) {return Math.abs(x-other.x)<halfX+other.halfX+gap-1e-8&&Math.abs(z-other.z)<halfZ+other.halfZ+gap-1e-8;}
        boolean overlapsCircle(double cx,double cz,double radius) {return Math.hypot(Math.max(0,Math.abs(x-cx)-halfX),Math.max(0,Math.abs(z-cz)-halfZ))<radius-1e-8;}
    }
    record Layout(List<Tree> trees,List<Bed> beds,List<Bench> benches) {}
    private record Cell(int col,int row,double x,double z) {}
    private record Patch(List<Cell> cells,Set<Long> occupied,double rx,double rz) {
        boolean inside(double x,double z,Building place) {
            return occupied.contains(cellKey((int)Math.floor((x-place.x)/CELL_SIZE+.5),(int)Math.floor((z-place.z)/CELL_SIZE+.5)));
        }
        boolean contains(Bounds box,Building place) {
            if(!inside(box.x,box.z,place))return false;
            for(int x:new int[]{-1,1})for(int z:new int[]{-1,1})if(!inside(box.x+x*box.halfX,box.z+z*box.halfZ,place))return false;
            return true;
        }
    }
    private record Spatial(String id,String type,double x,double z,double radius,double entranceX,double entranceZ) {}
    private record Key(Spatial place,List<Spatial> buildings) {}
    private static final Map<Key,Layout> CACHE=Collections.synchronizedMap(new LinkedHashMap<>(48,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key,Layout> entry){return size()>48;}
    });
    static Layout layout(State state,Building place) {
        if(state.geographyVersion<2||place.outdoorRadius<=0)return new Layout(List.of(),List.of(),List.of());
        Key key=new Key(spatial(place),state.buildings.values().stream().map(OutdoorPlanting::spatial).toList());
        return CACHE.computeIfAbsent(key,ignored->generate(place,List.copyOf(state.buildings.values())));
    }
    private static Spatial spatial(Building b) {
        Places.Entrance entrance=Places.entrance(b);
        return new Spatial(b.id,b.type,b.x,b.z,b.outdoorRadius,entrance.x(),entrance.z());
    }
    static double[] dimensions(Building b) {
        return new double[]{b.outdoorRadius*(switch(b.type){case "promenade"->.38;case "trekking_trail"->.48;case "river","riverside_walk"->.4;default->1;}),
            b.outdoorRadius*(switch(b.type){case "promenade","trekking_trail"->1.25;case "plaza"->.85;default->1;})};
    }
    private static Layout generate(Building place,List<Building> buildings) {
        Patch patch=patch(place,buildings);List<Bench> benches=benches(place,patch);List<Bed> beds=new ArrayList<>();
        if(place.type.equals("garden"))for(int i=0;i<patch.cells.size()&&beds.size()<16;i++) {
            Cell cell=patch.cells.get(i);
            if(i%33!=0||path(place,cell.x-place.x,cell.z-place.z,0)||Math.hypot(cell.x-place.x,cell.z-place.z)<=5)continue;
            Bounds bed=new Bounds(cell.x,cell.z,1.35,.7);
            if(!patch.contains(bed,place)||benches.stream().anyMatch(b->bed.overlaps(bounds(place,b),.3))
                ||beds.stream().anyMatch(b->bed.overlaps(new Bounds(b.x,b.z,1.35,.7),.3)))continue;
            beds.add(new Bed(cell.x,cell.z));
        }
        List<Tree> trees=new ArrayList<>();
        if(!place.id.contains("beach")&&!place.type.equals("plaza")) {
            String habitat=habitat(place.x,place.z);
            String[] palette=habitat.equals("mountain")?new String[]{"birch","alpine-pine","pine"}:habitat.equals("riverbank")?new String[]{"birch","willow","oak"}:new String[]{"birch","oak","pine"};
            double clearing=Math.max(8,Math.min(18,Math.min(patch.rx,patch.rz)*.28));
            List<Cell> candidates=new ArrayList<>(patch.cells);candidates.sort(Comparator.comparingDouble(p->seed(p.x,p.z)));
            List<Building> otherPlaces=buildings.stream().filter(b->!b.id.equals(place.id)).toList();
            int limit=Math.min(36,(int)Math.floor(patch.cells.size()*CELL_SIZE*CELL_SIZE/260));
            for(Cell cell:candidates) {
                if(trees.size()>=limit)break;
                int id=(int)Math.floor(seed(cell.x,cell.z)*1e7);String kind=palette[trees.size()%palette.length];
                double size=(.9+seed(id,3)*.35)*(kind.equals("pine")?.85:kind.equals("alpine-pine")?.65:1);
                double radius=(switch(kind){case "birch"->1.5;case "oak"->2.25;case "willow"->2.65;default->1.9;})*size;
                double x=cell.x+(seed(id,4)-.5)*1.4,z=cell.z+(seed(id,6)-.5)*1.4,lx=x-place.x,lz=z-place.z;
                if(Math.hypot(lx,lz)<clearing+radius||path(place,lx,lz,radius+1.8))continue;
                if(benches.stream().anyMatch(b->Math.hypot(lx-b.x,lz-b.z)<radius+3.5))continue;
                if(beds.stream().anyMatch(b->new Bounds(b.x,b.z,1.35,.7).overlapsCircle(x,z,radius+.3)))continue;
                if(trees.stream().anyMatch(t->Math.hypot(x-t.x,z-t.z)<radius+t.radius+4))continue;
                if(!plantingClear(x,z,radius,otherPlaces)||!patch.inside(x,z,place))continue;
                boolean inside=true;
                for(int i=0;i<12;i++)if(!patch.inside(x+Math.cos(i*Math.PI/6)*(radius+1.5),z+Math.sin(i*Math.PI/6)*(radius+1.5),place)){inside=false;break;}
                if(inside)trees.add(new Tree(id,kind,x,z,size,radius));
            }
        }
        return new Layout(List.copyOf(trees),List.copyOf(beds),List.copyOf(benches));
    }
    private static Patch patch(Building place,List<Building> buildings) {
        double[] dims=dimensions(place);double rx=dims[0],rz=dims[1];
        List<Building> obstacles=buildings.stream().filter(Places::enclosed).filter(b->Math.abs(b.x-place.x)<rx+6&&Math.abs(b.z-place.z)<rz+6).toList();
        Map<Long,Cell> cells=new LinkedHashMap<>();
        for(int row=-(int)Math.ceil(rz/CELL_SIZE);row<=Math.ceil(rz/CELL_SIZE);row++)for(int col=-(int)Math.ceil(rx/CELL_SIZE);col<=Math.ceil(rx/CELL_SIZE);col++) {
            double x=place.x+col*CELL_SIZE,z=place.z+row*CELL_SIZE;boolean clear=true;
            for(int dx:new int[]{-1,1})for(int dz:new int[]{-1,1})if(!patchClear(place,rx,rz,x+dx,z+dz,obstacles)){clear=false;break;}
            if(clear)cells.put(cellKey(col,row),new Cell(col,row,x,z));
        }
        Cell first=cells.values().stream().min(Comparator.comparingDouble(c->Math.hypot(c.x-place.x,c.z-place.z))).orElse(null);
        List<Cell> connected=new ArrayList<>();Set<Long> seen=new HashSet<>();List<Long> queue=new ArrayList<>();
        if(first!=null)queue.add(cellKey(first.col,first.row));
        for(int i=0;i<queue.size();i++) {
            long key=queue.get(i);Cell cell=cells.get(key);if(cell==null||!seen.add(key))continue;connected.add(cell);
            queue.add(cellKey(cell.col+1,cell.row));queue.add(cellKey(cell.col-1,cell.row));queue.add(cellKey(cell.col,cell.row+1));queue.add(cellKey(cell.col,cell.row-1));
        }
        return new Patch(List.copyOf(connected),Set.copyOf(seen),rx,rz);
    }
    private static boolean patchClear(Building b,double rx,double rz,double x,double z,List<Building> obstacles) {
        double dx=x-b.x,dz=z-b.z,angle=Math.atan2(dz/rz,dx/rx);
        if(Math.hypot(dx/rx,dz/rz)>1+.06*Math.sin(angle*3)+.035*Math.cos(angle*5))return false;
        TerrainElevation.River river=TerrainElevation.nearestRiver(x,z);
        if(!IslandGeography.dryLand(x,z)||river.distance()<=river.width()/2+.5||IslandGeography.roadDistance(x,z)<=3.4)return false;
        return obstacles.stream().noneMatch(p->Math.abs(p.x-x)<4.8&&Math.abs(p.z-z)<4.8);
    }
    private static List<Bench> benches(Building b,Patch patch) {
        List<Bench> candidates=new ArrayList<>(),benches=new ArrayList<>();
        for(double[] p:Seating.benches(b))candidates.add(new Bench(p[0],p[1],p[2]));
        for(int side:new int[]{-1,1})for(int end:new int[]{-1,1})candidates.add(new Bench(side*patch.rx*.55,end*patch.rz*.56,end<0?Math.PI:0));
        List<Bounds> occupied=new ArrayList<>();
        if(b.type.equals("hangout"))for(int x:new int[]{-4,4})for(int z:new int[]{-3,3})occupied.add(new Bounds(b.x+x,b.z+z,.125,.125));
        for(Bench p:candidates) {
            Bounds shape=bounds(b,p);
            if(!patch.contains(shape,b)||occupied.stream().anyMatch(other->shape.overlaps(other,.25)))continue;
            if((b.id.equals("park")||b.type.equals("plaza"))&&shape.overlapsCircle(b.x,b.z,2.25))continue;
            benches.add(p);occupied.add(shape);
        }
        return benches;
    }
    private static Bounds bounds(Building b,Bench p) {
        return new Bounds(b.x+p.x-Math.sin(p.rotation)*.0125,b.z+p.z-Math.cos(p.rotation)*.0125,p.rotated()?.4125:1.4,p.rotated()?1.4:.4125);
    }
    private static boolean path(Building b,double x,double z,double clearance) {
        return switch(b.type) {
            case "promenade","trekking_trail","river","riverside_walk"->Math.abs(x-Math.sin(z/9)*2.8)<1.4+clearance*Math.hypot(1,2.8/9);
            case "plaza"->true;
            case "garden"->Math.abs(z)<1.5+clearance||Math.abs(x-Math.floor(x/8+.5)*8)<1.2+clearance;
            default->Math.abs(x-Math.sin(z/9)*2.5)<1.35+clearance*Math.hypot(1,2.5/9)||Math.abs(z-Math.sin(x/12)*2)<1.15+clearance*Math.hypot(1,2/12);
        };
    }
    private static String habitat(double x,double z) {
        double coast=TerrainElevation.coastDistance(x,z);TerrainElevation.River river=TerrainElevation.nearestRiver(x,z);
        if(coast<48)return "coast";if(TerrainElevation.islandElevation(x,z)>36)return "mountain";
        if(river.distance()<river.width()/2+22)return "riverbank";
        return x>-440&&x<155&&z>-285&&z<285?"city":x< -445&&z<90&&z> -420?"forest":"meadow";
    }
    private static boolean plantingClear(double x,double z,double radius,List<Building> buildings) {
        if(!IslandGeography.onIsland(x,z)||TerrainElevation.coastDistance(x,z)<radius+3||!IslandLandscapes.clear(x,z))return false;
        TerrainElevation.River river=TerrainElevation.nearestRiver(x,z);
        if(river.distance()<river.width()/2+radius+1||trailDistance(x,z)<radius+2.6||IslandGeography.roadDistance(x,z)<radius+4.2)return false;
        for(var bridge:IslandLandscapes.SPEC.bridges())if(Math.abs(x-bridge.x())<bridge.length()/2+radius+5&&Math.abs(z-bridge.z())<bridge.width()/2+radius+2)return false;
        for(Building b:buildings) {
            if(b.outdoorRadius>0) {
                double[] dims=dimensions(b);
                if(Math.hypot((x-b.x)/dims[0],(z-b.z)/dims[1])<1.1+(radius+2)/Math.min(dims[0],dims[1]))return false;
            } else {
                double reserve=switch(b.type){case "wilderness","mountain","hill_station","trekking_trail","river","riverside_walk"->7;default->Places.enclosed(b)?6:8;};
                if(Math.abs(x-b.x)<reserve+radius&&Math.abs(z-b.z)<reserve+radius)return false;
            }
            Places.Entrance entrance=Places.entrance(b);if(Math.hypot(x-entrance.x(),z-entrance.z())<radius+4)return false;
        }
        double y=TerrainElevation.islandElevation(x,z);
        for(int i=0;i<8;i++) {
            double px=x+Math.cos(i*Math.PI/4)*radius,pz=z+Math.sin(i*Math.PI/4)*radius;
            if(!IslandLandscapes.clear(px,pz)||Math.abs(TerrainElevation.islandElevation(px,pz)-y)>Math.max(.35,radius*.65))return false;
        }
        return true;
    }
    private static double trailDistance(double x,double z) {
        double distance=Double.POSITIVE_INFINITY;
        for(var trail:IslandLandscapes.allTrails())for(int i=1;i<trail.size();i++) {
            var a=trail.get(i-1);var b=trail.get(i);double dx=b.get(0)-a.get(0),dz=b.get(1)-a.get(1);
            double fraction=Math.max(0,Math.min(1,((x-a.get(0))*dx+(z-a.get(1))*dz)/(dx*dx+dz*dz)));
            distance=Math.min(distance,Math.hypot(x-a.get(0)-dx*fraction,z-a.get(1)-dz*fraction));
        }
        return distance;
    }
    private static double seed(double value,double salt) {double result=Math.sin(value*127.1+salt*311.7)*43758.5453;return result-Math.floor(result);}
    private static long cellKey(int col,int row){return ((long)col<<32)|(row&0xffffffffL);}
}
