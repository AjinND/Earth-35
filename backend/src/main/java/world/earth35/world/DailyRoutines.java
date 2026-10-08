package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Stable preferences: assigned once, rather than rerolled every decision or restart. */
public final class DailyRoutines {
    public static final int SEVERE_HUNGER=98;
    public static final String FOOD_ADVICE="Plan supplies for the full day, not just the next meal or drink. Read supplies for meal and drink targets and quantities missing; BUY enough to reach those targets when funds and stock permit, before shops close or you go home to sleep. Keep a night snack in reserve. Check actual prices, stock, opening hours and money; if short, buy what you can and try another open shop or replenish later. Coffee is a drink, not a replacement for meals. Hunger wakes are rare and depend on inadequate meals or a long meal gap before bed, never a fixed interval. If self.hungerWakeNeedsFood, EAT an owned meal (or coffee if no meal remains) at home before resuming sleep. Do not go shopping during a hunger wake; replenish during waking hours. Severe hunger (98 or more) still requires a meal before sleep.";
    public record Supplies(int mealTarget,int drinkTarget,int mealsToBuy,int drinksToBuy) {}
    public static Supplies supplies(Resident a) {
        int meals=Math.max(3,(int)Math.ceil((a.hunger+1440*.035)/45)+1);
        return new Supplies(meals,3,Math.max(0,meals-a.inventory.getOrDefault("meal",0)),
            Math.max(0,3-a.inventory.getOrDefault("coffee",0)));
    }
    public static double hungerWakeThreshold(Resident a,long minute) {
        if(a.hunger<85||a.hungerWakeUntil>minute) return 101;
        boolean inadequate=a.lastMealMinute<0?a.hunger>=95:
            minute-a.lastMealMinute>=360||a.hungerAfterLastMeal>=70;
        if(!inadequate) return 101;
        // One reproducible chance per sleep episode, never a per-tick lottery.
        int roll=Math.floorMod(Objects.hash(a.id,minute),1000);
        return roll<100?98+roll/50.0:101;
    }
    private DailyRoutines() {}
    public static DailyRoutine derive(Resident a) {
        DailyRoutine r=new DailyRoutine();
        String text=(Objects.toString(a.personality,"")+" "+Objects.toString(a.goals,"")).toLowerCase(Locale.ROOT);
        int variation=Math.floorMod(Objects.toString(a.name,"").hashCode(),7)*10;
        boolean early=text.matches("(?s).*\\b(early bird|early riser|morning person|dawn|jogging|jog|runner|running)\\b.*");
        boolean late=text.matches("(?s).*\\b(night owl|late riser|late sleeper|sleeps in|sleep in)\\b.*");
        boolean moreSleep=text.matches("(?s).*\\b(sleepy|sleep more|loves sleep|long sleeper)\\b.*");
        r.wakeMinute=(early?300:late?480:moreSleep?450:390)+variation;
        int hours=moreSleep?9:early?8:7;
        r.sleepMinute=Math.floorMod(r.wakeMinute-hours*60,1440);
        r.morningActivity=text.matches("(?s).*\\b(jogging|jog|runner|running|fitness|athletic)\\b.*")?"jogging":
            text.contains("meditat")?"meditation":text.contains("read")?"reading":"breakfast";
        return r;
    }
    public static boolean sleepingTime(Resident a,long minute) {
        if(a.routine==null) return NightRoutine.isNight(minute); // legacy in-memory callers
        int time=(int)Math.floorMod(minute,1440),bed=a.routine.sleepMinute,wake=a.routine.wakeMinute;
        return bed>wake?time>=bed||time<wake:time>=bed&&time<wake;
    }
    public static long nextWake(Resident a,long minute) {
        if(a.routine==null) return NightRoutine.nextMorning(minute);
        long day=minute-Math.floorMod(minute,1440),wake=day+a.routine.wakeMinute;
        return wake>minute?wake:wake+1440;
    }
    public record SleepNeeds(boolean tired,int recommendedSleepMinutes,long expectedWakeMinute,int sleepDebtMinutes,String reason) {}
    public static int recoveryMinutes(Resident a) {
        int minutes=a.energy<30?480:a.energy<60?360:a.energy<80?240:120;
        minutes+=Math.min(120,a.sleepDebtMinutes/2)+(a.wetness>=40?60:0);
        return Math.min(600,minutes);
    }
    public static boolean needsSleep(Resident a,long minute) {
        boolean longDay=a.lastWakeMinute>=0&&minute-a.lastWakeMinute>=960;
        return a.energy<20||sleepingTime(a,minute)&&(a.energy<75||longDay||a.sleepDebtMinutes>=120);
    }
    public static long sleepUntil(Resident a,long minute,Integer hours) {
        if(hours!=null)Engine.require(hours>=1&&hours<=10,"Choose one to ten hours for SLEEP quantity");
        long preferred=sleepingTime(a,minute)?nextWake(a,minute):minute+120;
        long recovery=minute+recoveryMinutes(a);
        return Math.min(minute+600,hours==null?Math.max(preferred,recovery):Math.max(minute+hours*60,recovery));
    }
    public static SleepNeeds sleepNeeds(Resident a,long minute) {
        return new SleepNeeds(needsSleep(a,minute),recoveryMinutes(a),sleepUntil(a,minute,null),a.sleepDebtMinutes,
            a.hungerWakeNeedsFood?"Hunger woke you: eat a meal or drink from your inventory at home before returning to sleep":
            a.hunger>=SEVERE_HUNGER?"Severe hunger: eat a meal before sleeping again; plan a full day's supplies while shops are open":
            a.energy<60?"Low energy: allow recovery even beyond the preferred wake time":a.sleepDebtMinutes>0?"Recover lost sleep while considering your commitments":"Sleep times are preferences; choose sleep using energy, hunger, plans and work");
    }
    public static void configureJobs(State s) {
        for(Job j:s.jobs.values()) {
            switch(s.buildings.get(j.building).type) {
                case "coffee_shop" -> {j.startMinute=360;j.endMinute=840;}
                case "restaurant" -> {j.startMinute=660;j.endMinute=1140;}
                case "studio" -> {j.startMinute=600;j.endMinute=1080;}
                default -> {j.startMinute=540;j.endMinute=1020;}
            }
            j.workDays=new ArrayList<>(j.building.equals("office")||j.building.equals("studio")?List.of(0,1,2,3,4):List.of(0,1,2,3,4,5,6));
        }
    }
    public static boolean workOpen(Job j,long minute) {
        int time=(int)Math.floorMod(minute,1440),day=(int)Math.floorMod(Math.floorDiv(minute,1440),7);
        return j.workDays.contains(day)&&time>=j.startMinute&&time+60<=j.endMinute;
    }
    public static long nextShift(Job j,long minute) {
        long day=Math.floorDiv(minute,1440);
        for(int i=0;i<8;i++) {
            long start=(day+i)*1440+j.startMinute,end=(day+i)*1440+j.endMinute;
            if(j.workDays.contains((int)Math.floorMod(day+i,7))&&minute+60<=end) return Math.max(start,minute);
        }
        throw new IllegalStateException("Job has no workdays");
    }
    public static boolean onLeave(Resident a,long minute) {return a.leaveDay==Math.floorDiv(minute,1440);}
    public static int regularMinutesToday(Resident a,long minute) {
        return a.workDay==Math.floorDiv(minute,1440)?a.workedMinutes-a.overtimeMinutes:0;
    }
    /** Count only scheduled minutes; extra work must be explicitly chosen and paid. */
    public static int overtimeMinutes(Job j,Resident a,long minute) {
        int regular=0,remaining=Math.max(0,j.endMinute-j.startMinute-regularMinutesToday(a,minute));
        for(int i=0;i<60;i++) {
            long at=minute+i;int time=(int)Math.floorMod(at,1440);
            if(j.workDays.contains((int)Math.floorMod(Math.floorDiv(at,1440),7))&&time>=j.startMinute&&time<j.endMinute&&regular<remaining)regular++;
        }
        return 60-regular;
    }
    public static long overtimeHourlyWage(Job j) {return j.hourlyWage+j.hourlyWage/2;}
    public static boolean fullHourOpen(Building b,long minute) {
        for(int i=0;i<60;i++)if(!Places.open(b,minute+i))return false;
        return true;
    }
    public record WorkSchedule(String job,String building,int startMinute,int endMinute,List<Integer> workDays,
                               boolean open,long nextWorkMinute,long commuteAfter,int minutesWorkedToday,
                               boolean onLeave,int overtimeMinutesToday,long overtimeHourlyWage,boolean overtimeAvailable,WorkAccess.StaffAccessStatus staffAccess) {}
    public static WorkSchedule schedule(State s,Resident a) {
        Job j=s.jobs.get(a.job);if(j==null)return null;
        Building b=s.buildings.get(j.building);
        double distance=Travel.routeDistance(a,Travel.path(s,a,b,"shortest"));
        if(Places.enclosed(b)&&!b.id.equals(a.location))distance+=Math.hypot(b.x-Places.entrance(b).x(),b.z-Places.entrance(b).z());
        CityLayout layout=CityLayout.of(s);
        int walk=(int)Math.ceil(distance/layout.walkingSpeed())+3*(int)(distance/layout.spacing());
        boolean leave=onLeave(a,s.minute),complete=regularMinutesToday(a,s.minute)>=j.endMinute-j.startMinute;
        long next=nextShift(j,leave||complete?(Math.floorDiv(s.minute,1440)+1)*1440:s.minute);
        int extra=overtimeMinutes(j,a,s.minute);
        return new WorkSchedule(j.id,j.building,j.startMinute,j.endMinute,j.workDays,!leave&&!complete&&workOpen(j,s.minute),next,
            Math.max(s.minute,next-walk),a.workDay==Math.floorDiv(s.minute,1440)?a.workedMinutes:0,
            leave,a.workDay==Math.floorDiv(s.minute,1440)?a.overtimeMinutes:0,overtimeHourlyWage(j),
            !leave&&extra>0&&WorkAccess.fullHour(s,a,b,s.minute)&&WorkAccess.funded(s,a,j),WorkAccess.status(s,a));
    }
}
