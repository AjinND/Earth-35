package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Earned job wages stay reserved until the workday closes. Hourly receipts are private progress. */
public final class WorkPayroll {
    private WorkPayroll() {}
    public static class Workday {
        public String id,job,building,employer;
        public long day,scheduledEndMinute,payAtMinute,overtimeEndMinute;
        public long earnedCents;
        public int workedMinutes,overtimeMinutes;
    }
    public record Due(String job,String building,long day,long payAtMinute,int workedMinutes,int overtimeMinutes,long earnedCents) {}
    public static final String ADVICE="Job WORK uses decision blocks of up to one hour, with a shorter final regular block ending exactly at the scheduled close. Wages accumulate privately and are paid once at the scheduled "
        + "workday end, with one public shift-completion event. An hour's completed receipt means that work was performed, not that you were paid. "
        + "payroll lists earnedCents awaiting payment and payAtMinute in absolute world minutes. These earnings are not spendable money yet. "
        + "If you explicitly work beyond the scheduled end, payment waits until that day's accessible workplace closing time (midnight for an "
        + "all-day workplace), after any in-progress work finishes. Earlier overtime joins the scheduled-end payment. Quitting releases unused "
        + "reserved wages immediately, but already earned wages still arrive at the workday's payment time, even if you leave or change jobs. ";

    private static Map<String,Workday> days(Resident a) {
        if(a.workPayroll==null)a.workPayroll=new LinkedHashMap<>();
        return a.workPayroll;
    }
    public static int duration(Pending work) {return work.workDurationMinutes>0?work.workDurationMinutes:60;}
    public static int duration(Job job,long minute,boolean overtime) {
        int time=Math.floorMod(minute,1440);
        return !overtime&&job.workDays.contains((int)Math.floorMod(Math.floorDiv(minute,1440),7))&&time>=job.startMinute&&time<job.endMinute?
            Math.min(60,job.endMinute-time):60;
    }
    /** Snapshot the employer and closing time so a later resignation cannot lose earned pay. */
    public static void begin(State s,Resident a,Job job) {
        Pending work=a.pending;long start=work.until-duration(work),day=Math.floorDiv(start,1440);
        String id="payroll:"+a.id+":"+job.id+":"+day;
        Workday sheet=days(a).computeIfAbsent(id,key->{
            Workday fresh=new Workday();fresh.id=key;fresh.job=job.id;fresh.building=job.building;fresh.employer=Economy.employer(job);
            fresh.day=day;fresh.scheduledEndMinute=day*1440+job.endMinute;fresh.payAtMinute=fresh.scheduledEndMinute;return fresh;
        });
        long close=Math.max(work.until,(day+1)*1440);
        Building building=s.buildings.get(job.building);
        // The accepted block is fully accessible. Find the first closing after it, bounded by this day.
        for(long at=work.until;at<close;at++)if(!Places.canEnter(s,a,building,at)){close=at;break;}
        work.payrollId=id;work.payrollCloseMinute=close;
    }
    public static void accrue(State s,Resident a,Job job,int elapsed,int overtime,long earned,Engine.Effects effects) {
        Pending work=a.pending;
        // Existing saved hourly work has its own funded escrow, but no payroll metadata yet.
        if(work.payrollId==null||!days(a).containsKey(work.payrollId))begin(s,a,job);
        Workday sheet=days(a).get(work.payrollId);
        if(earned>0)Engine.pay(s,"escrow:"+work.id,sheet.id,earned,"earned_wage_accrual",effects);
        sheet.workedMinutes+=elapsed;sheet.overtimeMinutes+=overtime;
        sheet.earnedCents+=earned;
        if(elapsed>0&&(work.trackedWork?overtime>0&&work.lastWorkedMinute>sheet.scheduledEndMinute:work.until-duration(work)+elapsed>sheet.scheduledEndMinute)) {
            sheet.overtimeEndMinute=Math.max(sheet.overtimeEndMinute,work.payrollCloseMinute);
            sheet.payAtMinute=Math.max(sheet.payAtMinute,sheet.overtimeEndMinute);
        }
    }
    public static void completeBlock(State s,Resident a,Engine.Effects effects) {
        Pending work=a.pending;Job job=s.jobs.get(a.job);
        long reserved=s.accounts.getOrDefault("escrow:"+work.id,0L);
        accrue(s,a,job,duration(work),work.overtimeMinutes,reserved,effects);
        s.accounts.remove("escrow:"+work.id);
        Engine.finish(a,effects,"Work block recorded; earned wages are reserved for workday-end payment");
    }
    /** Wages follow validated minutes of attendance, including a resident's voluntary breaks. */
    public static void tick(State s,Resident a,Engine.Effects effects) {
        Pending p=a.pending;Job job=s.jobs.get(a.job);
        if(s.minute<=p.lastWorkedMinute)return;
        p.lastWorkedMinute=s.minute;p.workMinutes++;
        int time=Math.floorMod(s.minute-1,1440),day=(int)Math.floorMod(Math.floorDiv(s.minute-1,1440),7);
        int reservedRegular=duration(p)-p.overtimeMinutes;
        if(job.workDays.contains(day)&&time>=job.startMinute&&time<job.endMinute&&p.regularWorkMinutes<reservedRegular)p.regularWorkMinutes++;
        a.energy=Math.max(0,a.energy-.06);
        if(s.minute>=p.until) {
            if(p.workMinutes==duration(p))completeBlock(s,a,effects);
            else Employment.stopWork(s,a,effects,"Chosen work block ended");
        }
    }
    /** Run after activity completion, so the final minute is included before one atomic payment. */
    public static void settle(State s,Resident a,Engine.Effects effects) {
        if(a.workPayroll==null)return;
        var entries=a.workPayroll.values().iterator();
        while(entries.hasNext()) {
            Workday sheet=entries.next();
            boolean active=a.pending!=null&&"WORK".equals(a.pending.type)&&Objects.equals(a.pending.payrollId,sheet.id);
            if(s.minute<sheet.payAtMinute||active)continue;
            long earned=s.accounts.getOrDefault(sheet.id,0L);
            if(earned>0) {
                Engine.pay(s,sheet.id,a.id,earned,sheet.overtimeMinutes>0?"overtime_wage":"wage",effects);
                effects.facts.add(new Engine.Fact("employment",a.id,sheet.building,a.name+" completed their paid shift"
                    +(sheet.overtimeMinutes>0?" including overtime":"")+" and received "+earned+" cents for "+sheet.workedMinutes+" minutes of work."));
            }
            s.accounts.remove(sheet.id);entries.remove();
        }
    }
    public static List<Due> view(Resident a) {
        if(a.workPayroll==null)return List.of();
        return a.workPayroll.values().stream().map(p->new Due(p.job,p.building,p.day,
            a.pending!=null&&Objects.equals(a.pending.payrollId,p.id)&&a.pending.until>p.scheduledEndMinute?
                Math.max(p.payAtMinute,a.pending.payrollCloseMinute):p.payAtMinute,
            p.workedMinutes,p.overtimeMinutes,p.earnedCents))
            .sorted(Comparator.comparingLong(Due::payAtMinute)).toList();
    }
}
