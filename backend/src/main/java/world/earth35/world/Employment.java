package world.earth35.world;

import world.earth35.world.State.*;

/** Employment changes happen only in response to the resident's own explicit action. */
public final class Employment {
    private Employment() {}

    public static String quit(State s,Resident a,String target,Engine.Effects effects) {
        Job job=s.jobs.get(a.job);
        Engine.require(job!=null,"You have no current job to quit");
        Engine.require(!Engine.present(target)||job.id.equals(target),"QUIT_JOB target must be your current job or null");
        String settlement="";
        if(a.pending!=null&&"WORK".equals(a.pending.type))settlement=stopShift(s,a,job,effects);
        a.job="";a.offer="";a.occupation="Unemployed";
        a.leaveDay=-1;a.workPermit=null;
        effects.facts.add(new Engine.Fact("employment",a.id,job.building,a.name+" voluntarily left their job as "+job.title+"."));
        return "You left your job as "+job.title+"."+settlement+" Your former position is available; you may choose your next livelihood";
    }

    public static String stopWork(State s,Resident a,Engine.Effects effects,String reason) {
        Job job=s.jobs.get(a.job);
        if(job==null) {
            Pending p=a.pending;int duration=WorkPayroll.duration(p),elapsed=Math.min(duration,p.workMinutes),overtime=Math.max(0,elapsed-p.regularWorkMinutes);
            String escrow="escrow:"+p.id;long reserved=s.accounts.getOrDefault(escrow,0L);
            long earned=Math.min(reserved,p.workHourlyWage>0?(p.workHourlyWage*p.regularWorkMinutes+p.workOvertimeHourlyWage*overtime)/60:reserved*elapsed/duration);
            if(a.workPayroll==null)a.workPayroll=new java.util.LinkedHashMap<>();
            WorkPayroll.Workday sheet=a.workPayroll.get(p.payrollId);
            if(sheet==null) {
                sheet=new WorkPayroll.Workday();sheet.id="payroll:"+a.id+":"+p.id;sheet.job=a.job;sheet.building=p.target;
                sheet.employer=p.workEmployer==null?p.target:p.workEmployer;sheet.payAtMinute=Math.max(s.minute,p.until);sheet.scheduledEndMinute=sheet.payAtMinute;
                p.payrollId=sheet.id;a.workPayroll.put(sheet.id,sheet);
            }
            WorkPayroll.accrue(s,a,null,elapsed,overtime,earned,effects);
            if(reserved>earned)Engine.pay(s,escrow,sheet.employer,reserved-earned,"unused_wage_reservation",effects);
            s.accounts.remove(escrow);a.workedMinutes=Math.max(0,a.workedMinutes-duration+elapsed);a.overtimeMinutes=Math.max(0,a.overtimeMinutes-p.overtimeMinutes+overtime);
            effects.completions.add(new Engine.Completion(p.id,"cancelled",reason+"; earned pay retained despite missing job"));a.pending=null;
            WorkPayroll.settle(s,a,effects);return "Work stopped; earned pay retained";
        }
        return stopShift(s,a,job,effects,reason);
    }
    private static String stopShift(State s,Resident a,Job job,Engine.Effects effects) {return stopShift(s,a,job,effects,"Resident quit their job");}
    private static String stopShift(State s,Resident a,Job job,Engine.Effects effects,String reason) {
        Pending shift=a.pending;
        // Legacy WORK reserved sixty minutes; new final regular blocks can be shorter.
        int duration=WorkPayroll.duration(shift);long start=shift.until-duration;
        int elapsed=shift.trackedWork?shift.workMinutes:(int)Math.max(0,Math.min(duration,s.minute-start));
        int regularBefore=Math.max(0,a.workedMinutes-a.overtimeMinutes-(duration-shift.overtimeMinutes));
        int remaining=Math.max(0,job.endMinute-job.startMinute-regularBefore),regular=0;
        for(int i=0;i<elapsed;i++) {
            long minute=start+i;
            int time=Math.floorMod(minute,1440),day=(int)Math.floorMod(Math.floorDiv(minute,1440),7);
            if(job.workDays.contains(day)&&time>=job.startMinute&&time<job.endMinute&&regular<remaining)regular++;
        }
        if(shift.trackedWork)regular=shift.regularWorkMinutes;
        int overtime=elapsed-regular;
        String escrow="escrow:"+shift.id;
        long reserved=s.accounts.getOrDefault(escrow,0L);
        long earned=Math.min(reserved,(job.hourlyWage*regular+DailyRoutines.overtimeHourlyWage(job)*overtime)/60);
        WorkPayroll.accrue(s,a,job,elapsed,overtime,earned,effects);
        if(reserved>earned)Engine.pay(s,escrow,Economy.employer(job),reserved-earned,"unused_wage_reservation",effects);
        s.accounts.remove(escrow);
        a.workedMinutes=Math.max(0,a.workedMinutes-(duration-elapsed));
        a.overtimeMinutes=Math.max(0,a.overtimeMinutes-(shift.overtimeMinutes-overtime));
        effects.completions.add(new Engine.Completion(shift.id,"cancelled",reason+" after "+elapsed+" minutes; earned wages of "+earned+" cents reserved for workday-end payment and unused reservation returned to employer"));
        a.pending=null;
        WorkPayroll.settle(s,a,effects);
        return " Your work stopped after "+elapsed+" minutes; "+earned+" cents earned will be paid at the workday's payment time.";
    }
}
