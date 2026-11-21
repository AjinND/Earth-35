package world.earth35.protocol;

import java.util.*;
import world.earth35.world.*;
import world.earth35.world.State.*;

/** A resident's day so far and real upcoming constraints. Every choice remains theirs. */
public final class DailyLifeContext {
    private DailyLifeContext() {}
    public static final String ADVICE="Read dailyLife alongside awareness and cognition. Let your day have continuity: choose a manageable "
        + "next step toward your own intention, carry it through travel and ordinary interruptions, and change it when evidence or your priorities change. "
        + "Use rhythm.recentDays to remember time actually spent and completed actions, including useful repetition and unfinished work. "
        + "A missing or partial day is unknown history; UNSCHEDULED time includes waiting for the runtime, not proof of laziness or boredom. "
        + "An activity inherited from an old save without its command receipt can have recorded time but no completed-command count. "
        + "There is no daily quota for work, exercise, study or socializing. Your personality, chosen interests, relationships, memories and current "
        + "self-assessment should explain which activities appeal to you; do not copy a generic ideal day or change activities just for variety. "
        + "Rest, familiar habits, quiet time and unproductive leisure can be worthwhile. Choose them deliberately; repeated LOOK is only observation. "
        + "Think ahead before a long activity: allow time for an owned meal, supplies, the journey home, recovery and actual accepted plans. "
        + "upcoming distinguishes accepted meetings, borrowed-book deadlines and optional work/sleep preferences; it never means someone has arrived "
        + "or that you must attend work. walkingMinutes and leaveByMinute are estimates from your current position, not guaranteed arrivals. "
        + "When early, use the available time meaningfully; when a plan is infeasible, revise it honestly instead of repeatedly attempting the same step. "
        + "After reaching a place, do the next feasible activity that brought you there or reconsider using what you actually find. "
        + "When an aim is finished or abandoned, clear or replace the intention. Mood can color a choice but does not grant abilities or reveal others' feelings. ";

    public record Commitment(String kind,String id,String target,long minute,Long expiresMinute,Integer walkingMinutes,
                             Long leaveByMinute,String description) {}
    public record View(long day,int minuteOfDay,DailyLife.View rhythm,Long minutesSinceMeal,Long minutesAwake,
                       List<Commitment> upcoming,List<String> considerations) {}

    public static View view(AgentService.Observation obs) {
        Resident a=obs.self();long minute=obs.worldMinute();
        List<Commitment> upcoming=new ArrayList<>();List<String> considerations=new ArrayList<>();
        if(obs.social()!=null)for(SocialPlan plan:obs.social().plans())
            if(List.of("accepted","in_progress").contains(plan.status)&&plan.expires>minute&&(a.id.equals(plan.proposer)||a.id.equals(plan.invited))) {
                var task="in_progress".equals(plan.status)?plan.tasks.get(a.id):null;
                upcoming.add(commitment(obs,task==null?"accepted_meeting":"shared_activity",plan.id,
                    task==null?plan.destination:task.destination(),task==null?plan.meetingMinute:plan.expires,plan.expires,
                    task==null?"You both accepted: "+plan.activity+". Check actual presence on arrival.":
                        "Your agreed task: "+task.activity()+". Completion requires the actual world action."));
            }
        for(BookLoan loan:a.bookLoans.values())upcoming.add(commitment(obs,"book_return",loan.book,"library",loan.dueMinute,null,
            loan.dueMinute<=minute?"Borrowed book is overdue; return it during library opening hours":"Return the borrowed book during library opening hours"));
        var work=obs.workSchedule();
        if(work!=null)upcoming.add(commitment(obs,"optional_work",work.job(),work.building(),work.nextWorkMinute(),null,
            work.onLeave()?"You chose leave today; this is the next work opportunity":"Available work if you choose to attend; review money, hours and today's completed work"));
        if(a.routine!=null) {
            long bedtime=minute-Math.floorMod(minute,1440)+a.routine.sleepMinute;
            if(DailyRoutines.sleepingTime(a,minute))bedtime=minute;
            else if(bedtime<minute)bedtime+=1440;
            upcoming.add(commitment(obs,"preferred_sleep",null,a.home,bedtime,null,
                a.nightPlan!=null&&a.nightPlan.until>minute?"Preferred sleep window; you chose a night plan until "+a.nightPlan.until:
                    "Preferred sleep window; decide using energy, sleep debt and your plans"));
        }
        Long sinceMeal=a.lastMealMinute<0?null:Math.max(0,minute-a.lastMealMinute);
        Long awake=a.lastWakeMinute<0||a.pending!=null&&"SLEEP".equals(a.pending.type)?null:Math.max(0,minute-a.lastWakeMinute);
        if(a.inventory.getOrDefault("meal",0)==0)considerations.add("No meals in inventory. Check affordable stock and opening hours before a long outing or sleep.");
        else if(a.hunger>=55)considerations.add("You have food and rising hunger. Consider eating before a long activity rather than waiting for severe hunger.");
        if(DailyRoutines.needsSleep(a,minute))considerations.add("Your body needs recovery. Include the journey home when deciding when to sleep.");
        if(a.arrears>0)considerations.add("Unpaid rent is "+a.arrears+" cents. Weigh available income and essential costs before discretionary spending.");
        if(a.mind!=null&&a.mind.intention!=null&&a.pending==null&&Objects.equals(a.location,a.mind.intention.target()))
            considerations.add("You are at your intention's destination. Follow through on its next step or revise it using what you find here.");
        upcoming.sort(Comparator.comparingLong(Commitment::minute).thenComparing(Commitment::kind));
        return new View(Math.floorDiv(minute,1440),(int)Math.floorMod(minute,1440),DailyLife.view(a,minute),sinceMeal,awake,
            List.copyOf(upcoming),List.copyOf(considerations));
    }
    private static Commitment commitment(AgentService.Observation obs,String kind,String id,String target,long at,Long expires,String description) {
        Integer walking=null;
        if(Objects.equals(obs.self().location,target))walking=0;
        else if(obs.travel()!=null)walking=obs.travel().destinations().stream().filter(d->Objects.equals(d.destination(),target))
            .flatMap(d->d.routes().stream()).mapToInt(Travel.Option::walkingMinutes).min().stream().boxed().findFirst().orElse(null);
        return new Commitment(kind,id,target,at,expires,walking,walking==null?null:at-walking,description);
    }
}
