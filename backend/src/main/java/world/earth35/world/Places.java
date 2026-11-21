package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Entrances and venue hours shared by routing, actions and the rendered city. */
public final class Places {
    private Places() {}
    public record Entrance(String side,double x,double z) {}
    public record Hours(int openMinute,int closeMinute,List<Integer> openDays,Boolean openNow,Long nextOpenMinute) {}
    public static boolean enclosed(Building b) {
        return b!=null&&!List.of("park","garden","plaza","promenade","hangout","bus_stop","river","riverside_walk","mountain","hill_station","trekking_trail","wilderness").contains(b.type);
    }
    public static Entrance entrance(Building b) {
        return b.entrance!=null?b.entrance:new Entrance(enclosed(b)?"front":"open",b.x,b.z+(enclosed(b)?3.6:0));
    }
    public static void configure(State s) {
        for(Building b:s.buildings.values()) {
            b.entrance=entrance(b);
            switch(b.type) {
                case "coffee_shop" -> {b.openMinute=360;b.closeMinute=1200;}
                case "grocery","market" -> {b.openMinute=480;b.closeMinute=1200;}
                case "restaurant" -> {b.openMinute=660;b.closeMinute=1380;}
                case "library" -> {b.openMinute=480;b.closeMinute=1200;}
                case "community_center" -> {b.openMinute=480;b.closeMinute=1320;}
                case "mall" -> {b.openMinute=600;b.closeMinute=1320;}
                default -> { /* Homes and outdoor places remain accessible all day. */ }
            }
        }
    }
    public static boolean open(Building b,long minute) {
        if(b==null)return false;
        if(b.type.equals("construction"))return false;
        int time=(int)Math.floorMod(minute,1440);long day=Math.floorDiv(minute,1440);
        if(b.openMinute==b.closeMinute)return b.openDays.contains((int)Math.floorMod(day,7));
        if(b.openMinute<b.closeMinute)return b.openDays.contains((int)Math.floorMod(day,7))&&time>=b.openMinute&&time<b.closeMinute;
        // An overnight service belongs to the day on which it opened.
        return time>=b.openMinute?b.openDays.contains((int)Math.floorMod(day,7)):
            time<b.closeMinute&&b.openDays.contains((int)Math.floorMod(day-1,7));
    }
    public static long nextOpen(Building b,long minute) {
        if(open(b,minute))return minute;
        long day=Math.floorDiv(minute,1440);
        for(int i=0;i<8;i++) {
            long candidate=(day+i)*1440+b.openMinute;
            if(candidate>=minute&&open(b,candidate))return candidate;
        }
        return -1;
    }
    public static Hours hours(Building b,long minute) {
        return new Hours(b.openMinute,b.closeMinute,List.copyOf(b.openDays),open(b,minute),nextOpen(b,minute));
    }
    public static Hours travelHours(State s,Resident a,Building b) {
        var hours=hours(b,s.minute);
        if(a.pending!=null&&!b.id.equals(a.location)&&(b.id.equals(a.pending.target)||b.id.equals(a.pending.finalDestination))) {
            var door=entrance(b);
            if(Math.hypot(a.x-door.x(),a.z-door.z())>1)
                return new Hours(hours.openMinute(),hours.closeMinute(),hours.openDays(),null,null);
        }
        return hours;
    }
    public static void requireOpen(Building b,long minute) {
        Engine.require(open(b,minute),b.name+" is closed; opening hours "+NightRoutine.timeOfDay(b.openMinute)+"–"+NightRoutine.timeOfDay(b.closeMinute)+". Choose an open place or return after opening.");
    }
    public static boolean canEnter(State s,Resident a,Building b,long minute) {
        return open(b,minute)||WorkAccess.approved(s,a,b,minute);
    }
    /** Closing ends public visits, including stale occupants restored from a saved world. */
    static void closeVisit(State s,Resident a,Engine.Effects effects) {
        Building b=s.buildings.get(a.location);
        boolean departing=Travel.moving(a)&&a.pending.exitDoor!=null&&!a.pending.entering;
        if(!enclosed(b)&&departing) {
            // Departures say "road" before the resident has crossed the exit door.
            double[] exit=a.pending.exitDoor;
            b=s.buildings.values().stream().filter(Places::enclosed).filter(place->{
                Entrance door=entrance(place);
                return Math.hypot(door.x()-exit[0],door.z()-exit[1])<.001;
            }).findFirst().orElse(null);
        }
        if(!enclosed(b)||canEnter(s,a,b,s.minute))return;
        Pending p=a.pending;
        // Accepted work has already reserved wages and access for its whole block.
        // Expiry prevents new work; the accepted block finishes before staff leave.
        if(p!=null&&"WORK".equals(p.type)&&b.id.equals(p.target)&&s.minute<p.until)return;
        String reason=b.name+" is closed; you are outside its front entrance. Choose an open place or return after opening";
        if(p!=null&&!departing) {
            if(List.of("RESEARCH_PROJECT","BUILD_PROJECT").contains(p.type))Projects.interrupt(s,a,effects,reason);
            else if("PRODUCE_PRODUCT".equals(p.type))Ventures.interrupt(s,a,effects,reason);
            else {
                effects.completions.add(new Engine.Completion(p.id,"cancelled",reason));
                a.pending=null;
            }
        }
        Conversations.end(s,a);
        Entrance door=entrance(b);
        a.x=door.x();a.z=door.z();a.location="road";
        if(departing) {
            p.socialWith=null;p.socialUntil=0;p.waitingReason=null;p.exitDoor=null;
            p.route=Travel.path(s,a,Travel.walkingDestination(s,p),p.routeChoice==null?"shortest":p.routeChoice);
            p.frontDoorRoute=true;
        }
        effects.facts.add(new Engine.Fact("daily_life",a.id,b.id,a.name+" left "+b.name+" through its front entrance because it is closed."));
    }
    public static void requireEntry(State s,Resident a,Building b,long minute) {
        Engine.require(!b.type.equals("construction"),b.name+" is under construction; choose a completed building or nearby outdoor land");
        if(canEnter(s,a,b,minute))return;
        Job job=s.jobs.get(a.job);
        if(job!=null&&job.building.equals(b.id))
            throw new Engine.Invalid(b.name+" is closed. Request overtime staff access with REQUEST_OVERTIME; if unavailable, wait for opening or choose another activity");
        requireOpen(b,minute);
    }
}
