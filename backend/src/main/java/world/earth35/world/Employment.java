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

    private static String stopShift(State s,Resident a,Job job,Engine.Effects effects) {
        Pending shift=a.pending;
        // WORK reserves one full hour. Infer its start for worlds saved before startedAt was recorded.
        long start=shift.until-60;
        int elapsed=(int)Math.max(0,Math.min(60,s.minute-start));
        int regularBefore=Math.max(0,a.workedMinutes-a.overtimeMinutes-(60-shift.overtimeMinutes));
        int remaining=Math.max(0,job.endMinute-job.startMinute-regularBefore),regular=0;
        for(int i=0;i<elapsed;i++) {
            long minute=start+i;
            int time=Math.floorMod(minute,1440),day=(int)Math.floorMod(Math.floorDiv(minute,1440),7);
            if(job.workDays.contains(day)&&time>=job.startMinute&&time<job.endMinute&&regular<remaining)regular++;
        }
        int overtime=elapsed-regular;
        String escrow="escrow:"+shift.id;
        long reserved=s.accounts.getOrDefault(escrow,0L);
        long earned=Math.min(reserved,(job.hourlyWage*regular+DailyRoutines.overtimeHourlyWage(job)*overtime)/60);
        if(earned>0)Engine.pay(s,escrow,a.id,earned,overtime>0?"overtime_wage":"wage",effects);
        if(reserved>earned)Engine.pay(s,escrow,Economy.employer(job),reserved-earned,"unused_wage_reservation",effects);
        s.accounts.remove(escrow);
        a.workedMinutes=Math.max(0,a.workedMinutes-(60-elapsed));
        a.overtimeMinutes=Math.max(0,a.overtimeMinutes-(shift.overtimeMinutes-overtime));
        effects.completions.add(new Engine.Completion(shift.id,"cancelled","Resident quit their job after "+elapsed+" minutes; earned wages of "+earned+" cents settled and unused reservation returned to employer"));
        a.pending=null;
        return " Your shift ended after "+elapsed+" minutes and paid "+earned+" cents for the time worked.";
    }
}
