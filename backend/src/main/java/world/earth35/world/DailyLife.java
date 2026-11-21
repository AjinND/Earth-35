package world.earth35.world;

import java.util.*;
import world.earth35.world.State.Resident;

/** Private evidence of how time was spent, never a schedule or a personality score. */
public final class DailyLife {
    private DailyLife() {}
    public static final int DAYS=7;
    public static class History {
        public Long trackedSince;
        public long lastTick=-1,stretchMinutes;
        public String lastActivity;
        public Map<Long,Day> days=new LinkedHashMap<>();
    }
    public static class Day {
        public int observedMinutes;
        public Map<String,Integer> activityMinutes=new LinkedHashMap<>();
        public Map<String,Integer> completedActions=new LinkedHashMap<>();
    }
    public record DayView(long day,int observedMinutes,Map<String,Integer> activityMinutes,Map<String,Integer> completedActions) {}
    public record View(Long trackedSince,String currentActivity,long currentStretchMinutes,List<DayView> recentDays) {}

    public static String activity(Resident a) {
        if(a.pending!=null&&a.pending.activityPaused)return "BREAK";
        return a.pending!=null?a.pending.type:a.paused?"PAUSED":"UNSCHEDULED";
    }
    private static History history(Resident a,long minute) {
        Cognition.Mind mind=Cognition.mind(a);
        if(mind.dailyLife==null)mind.dailyLife=new History();
        if(mind.dailyLife.trackedSince==null)mind.dailyLife.trackedSince=minute;
        return mind.dailyLife;
    }
    private static Day day(History h,long minute) {
        long day=Math.floorDiv(minute,1440);
        h.days.keySet().removeIf(d->d<day-DAYS+1);
        return h.days.computeIfAbsent(day,d->new Day());
    }
    /** Called before progressing the activity for the interval [minute-1, minute). */
    public static void tick(Resident a,long minute) {
        History h=history(a,minute-1);
        if(minute<=h.lastTick)return;
        String activity=activity(a);
        h.stretchMinutes=minute==h.lastTick+1&&Objects.equals(activity,h.lastActivity)?h.stretchMinutes+1:1;
        h.lastActivity=activity;h.lastTick=minute;
        Day day=day(h,minute-1);day.observedMinutes++;
        day.activityMinutes.merge(activity,1,Integer::sum);
    }
    /** Receipt callers guarantee exactly one transition to completed. Attempts are not achievements. */
    public static void completed(Resident a,long minute,String action) {
        if(Set.of("LOOK","CONTINUE_TRAVEL","CONTINUE_ACTIVITY","PAUSE_ACTIVITY","RESUME_ACTIVITY","STOP_ACTIVITY").contains(action))return;
        day(history(a,minute),minute).completedActions.merge(action,1,Integer::sum);
    }
    public static View view(Resident a,long minute) {
        History h=a.mind==null||a.mind.dailyLife==null?new History():a.mind.dailyLife;
        long day=Math.floorDiv(minute,1440);
        List<DayView> days=h.days.entrySet().stream().filter(e->e.getKey()>=day-DAYS+1&&e.getKey()<=day)
            .sorted(Map.Entry.<Long,Day>comparingByKey().reversed())
            .map(e->new DayView(e.getKey(),e.getValue().observedMinutes,Map.copyOf(e.getValue().activityMinutes),
                Map.copyOf(e.getValue().completedActions))).toList();
        long stretch=h.lastTick==minute&&Objects.equals(activity(a),h.lastActivity)?h.stretchMinutes:0;
        return new View(h.trackedSince,activity(a),stretch,days);
    }
}
