package world.earth35.world;

import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.Resident;

/** A runtime default for an unoccupied night, not a compulsory world schedule. */
public final class NightRoutine {
    private NightRoutine() {}
    public static boolean isNight(long minute) {
        long time=Math.floorMod(minute,1440);
        return time>=1200||time<360;
    }
    public static long nextMorning(long minute) {
        long time=Math.floorMod(minute,1440);
        return minute-time+(time<360?360:1800);
    }
    public static String timeOfDay(long minute) {
        long time=Math.floorMod(minute,1440);
        return String.format(java.util.Locale.ROOT,"%02d:%02d",time/60,time%60);
    }
    public static Command defaultAction(State state,Resident self,String requestId) {
        if(self.paused||self.pending!=null||Conversations.active(state,self)) return null;
        if(self.home!=null&&self.home.equals(self.location)) {
            if(self.hungerWakeNeedsFood) {
                String item=self.inventory.getOrDefault("meal",0)>0?"meal":self.inventory.getOrDefault("coffee",0)>0?"coffee":null;
                return item==null?null:new Command(requestId,ActionType.EAT,null,null,item,null,null);
            }
            if(self.resumeSleepUntil>state.minute&&self.hunger<DailyRoutines.SEVERE_HUNGER)
                return new Command(requestId,ActionType.SLEEP,null,null,null,null,null);
        }
        if(!DailyRoutines.needsSleep(self,state.minute)||self.hunger>=DailyRoutines.SEVERE_HUNGER||self.paused||self.pending!=null
            ||self.nightPlan!=null&&self.nightPlan.until>state.minute) return null;
        if(self.home==null||!state.buildings.containsKey(self.home)) return null;
        return new Command(requestId,self.home.equals(self.location)?ActionType.SLEEP:ActionType.MOVE,
            self.home.equals(self.location)?null:self.home,null,null,null,null);
    }
    public static Command resolve(State state,Resident self,Command proposed) {
        // Explicit rest, sleep and social activities remain the resident's choice.
        boolean unoccupied=proposed.action()==ActionType.LOOK;
        Command fallback=unoccupied?defaultAction(state,self,proposed.requestId()):null;
        return fallback==null?proposed:fallback;
    }
}
