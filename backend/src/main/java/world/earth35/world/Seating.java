package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Finite seating chosen by residents, held only for the duration of an activity. */
public final class Seating {
    private Seating() {}
    public record View(String location,List<String> objects,int capacity,int occupied,int availableSeats) {}
    public static int capacity(Building place) {
        if(place==null||!place.restAllowed||place.objects.isEmpty())return 0;
        // Outdoor slots match gathering-furniture.ts: two residents per bench.
        int seats=switch(place.type) {
            case "park","garden","plaza","promenade","hangout" -> 4;
            case "river","riverside_walk","mountain","hill_station","trekking_trail" -> 2;
            default -> place.objects.values().stream().mapToInt(label->{
                String name=label.toLowerCase(Locale.ROOT);
                return name.contains("table")?4:name.contains("sofa")?3:name.contains("bench")?2:1;
            }).sum();
        };
        return Math.min(place.capacity,seats);
    }
    public static Integer seatIndex(Resident resident) {
        Pending p=resident.pending;
        return p!=null&&List.of("REST","READ_BOOK").contains(p.type)?p.seatIndex:null;
    }
    public static View view(Building place,Collection<Integer> indices) {
        int capacity=capacity(place);
        long occupied=indices.stream().filter(Objects::nonNull).filter(i->i>=0&&i<capacity).distinct().count();
        return new View(place==null?null:place.id,place==null?List.of():List.copyOf(place.objects.keySet()),
            capacity,(int)occupied,capacity-(int)occupied);
    }
    static void reserve(State state,Resident resident,Pending activity,boolean required) {
        int capacity=capacity(state.buildings.get(resident.location));
        BitSet occupied=new BitSet(capacity);
        for(Resident other:state.agents.values())if(resident.location.equals(other.location)) {
            Integer index=seatIndex(other);
            if(index!=null&&index>=0&&index<capacity)occupied.set(index);
        }
        int index=occupied.nextClearBit(0);
        Engine.require(!required||index<capacity,"All seating here is occupied; choose another activity, REST without a seat, or wait for a free seat");
        activity.seatIndex=index<capacity?index:null;
    }
}
