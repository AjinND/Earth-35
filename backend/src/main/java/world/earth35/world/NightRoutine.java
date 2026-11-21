package world.earth35.world;

import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;
import java.util.*;

/** Advisory sleep context, with compatibility helpers for older runtimes. */
public final class NightRoutine {
    private NightRoutine() {}
    public static final String ADVICE="nightReturnDue is an advisory cue that your preferred night has arrived while away from home, "
        + "without a current night plan or accepted meeting. It is not a departure order or a restriction on your choices. "
        + "Weigh energy, hunger, sleep debt, company, interests and tomorrow's commitments when choosing whether to stay out, go home or sleep. "
        + "Casual conversation, outdoor rest and unplanned leisure remain available; no night plan is required for permission. "
        + "A nightPlan can record an intention, and you may revise your priorities. At home, severe hunger still affects whether sleep is physically possible. ";
    private static boolean planned(Resident self,long minute,Collection<SocialPlan> plans) {
        return self.nightPlan!=null&&self.nightPlan.until>minute||plans.stream().anyMatch(p->
            List.of("accepted","in_progress").contains(p.status)&&p.expires>minute&&(self.id.equals(p.proposer)||self.id.equals(p.invited)));
    }
    public static boolean returnHomeDue(Resident self,long minute,Collection<SocialPlan> plans) {
        return !self.paused&&self.pending==null&&self.home!=null&&!self.home.isBlank()&&!self.home.equals(self.location)
            &&(self.bedtimeRoutine||DailyRoutines.sleepingTime(self,minute))&&!planned(self,minute,plans);
    }
    public static boolean returnHomeDue(State state,Resident self) {
        return returnHomeDue(self,state.minute,state.socialPlans.values())&&state.buildings.containsKey(self.home);
    }
    public static boolean idlingAction(ActionType action) {
        return List.of(ActionType.LOOK,ActionType.TALK,ActionType.REST,ActionType.USE_OBJECT,
            ActionType.CALL_OUT,ActionType.PAUSE_TRAVEL).contains(action);
    }
    /** Retained for older callers; sleep preferences never reject a voluntary action. */
    public static void requireNightChoice(State state,Resident self,Command proposed) {
    }
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
        return null;
    }
    public static Command resolve(State state,Resident self,Command proposed) {
        return proposed;
    }
}
