package world.earth35.world;

import java.util.*;

/** Versioned spatial units shared by streets, journeys, traffic and public maps. */
public record CityLayout(int version,double centerX,double centerZ,double spacing,double halfExtent,
                         double metersPerUnit,double walkingSpeed,double runningSpeed,double vehicleSpeed) {
    public static final CityLayout PROTOTYPE=new CityLayout(0,0,0,12,72,10,2,4,6);
    public static final CityLayout ISLAND=new CityLayout(1,-150,0,48,268,10,8,16,24);
    public static final CityLayout ORGANIC=new CityLayout(2,-150,0,48,268,10,8,16,24);
    public static final CityLayout LANDSCAPES=new CityLayout(3,-150,0,48,268,10,8,16,24);
    public static final CityLayout CONNECTED=new CityLayout(4,-150,0,48,268,10,8,16,24);
    public static CityLayout of(State s) {return s!=null&&s.geographyVersion>=4?CONNECTED:s!=null&&s.geographyVersion>=3?LANDSCAPES:s!=null&&s.geographyVersion>=2?ORGANIC:s!=null&&s.geographyVersion>=1?ISLAND:PROTOTYPE;}
    public double x(double original) {return centerX+original*spacing/12;}
    public double z(double original) {return centerZ+original*spacing/12;}
    public double nearestRoad(double value,boolean east) {
        double origin=east?centerX:centerZ;
        return origin+Math.round((value-origin)/spacing)*spacing;
    }
    public boolean onRoad(double x,double z) {return version>=2?IslandGeography.roadDistance(x,z)<1.6:Math.abs(x-nearestRoad(x,true))<1.6||Math.abs(z-nearestRoad(z,false))<1.6;}
    public List<Double> roads(boolean east) {
        double origin=east?centerX:centerZ;
        return java.util.stream.IntStream.rangeClosed(-5,5).mapToObj(i->origin+i*spacing).toList();
    }
    public double minimumRoad(boolean east) {return (east?centerX:centerZ)-5*spacing;}
    public double maximumRoad(boolean east) {return (east?centerX:centerZ)+5*spacing;}
    public double[] sidewalks(double coordinate,boolean east) {
        double lower=minimumRoad(east)-2.4,upper=maximumRoad(east)+2.4;
        for(double road:roads(east))for(double offset:new double[]{-2.4,2.4}) {
            double point=road+offset;
            if(point<=coordinate+.000001)lower=Math.max(lower,point);
            if(point>=coordinate-.000001)upper=Math.min(upper,point);
        }
        return Math.abs(lower-upper)<.000001?new double[]{lower}:new double[]{lower,upper};
    }
    /** Move a lane/sidewalk by its centerline while retaining its actual width. */
    public double migrateAxis(double value,boolean east) {
        double road=Math.round(value/12)*12,offset=value-road;
        return (east?centerX:centerZ)+road*4+(Math.abs(offset)<=2.5?offset:offset*4);
    }
}
