package world.earth35.world;

import world.earth35.world.State.*;

/** Employer policy grants individual staff access without opening a venue to customers. */
public final class WorkAccess {
    private WorkAccess() {}
    public record StaffAccessStatus(boolean canRequest,boolean approved,Long approvedUntil,int earlyAccessMinutes,
                         int lateAccessMinutes,boolean outsideHoursWorkAllowed,String reason) {}
    static long windowEnd(Job j,long minute) {
        return Math.floorDiv(minute,1440)*1440+Math.min(1440,j.endMinute+Math.max(0,j.lateAccessMinutes));
    }
    static boolean staffWindow(Job j,long minute) {
        long day=Math.floorDiv(minute,1440);int time=(int)Math.floorMod(minute,1440);
        return j.outsideHoursWorkAllowed&&j.workDays.contains((int)Math.floorMod(day,7))
            &&time>=Math.max(0,j.startMinute-Math.max(0,j.earlyAccessMinutes))
            &&minute<windowEnd(j,minute);
    }
    public static boolean approved(State s,Resident a,Building b,long minute) {
        Job j=s.jobs.get(a.job);WorkPermit p=a.workPermit;
        return j!=null&&b!=null&&j.building.equals(b.id)&&p!=null&&j.id.equals(p.job)
            &&!DailyRoutines.onLeave(a,minute)&&minute>=p.from&&minute<p.until&&staffWindow(j,minute);
    }
    public static boolean fullHour(State s,Resident a,Building b,long minute) {
        for(int i=0;i<60;i++)if(!Places.canEnter(s,a,b,minute+i))return false;
        return true;
    }
    public static boolean funded(State s,Resident a,Job j) {
        int extra=DailyRoutines.overtimeMinutes(j,a,s.minute);
        long regular=j.hourlyWage*(60-extra)/60;
        long wage=(j.hourlyWage*(60-extra)+DailyRoutines.overtimeHourlyWage(j)*extra)/60;
        String account=Economy.employer(j);
        return s.accounts.getOrDefault(account,0L)-Math.max(0,Economy.remainingPayroll(s,account)-regular)>=wage;
    }
    public static StaffAccessStatus status(State s,Resident a) {
        Job j=s.jobs.get(a.job);if(j==null)return null;
        String reason=DailyRoutines.onLeave(a,s.minute)?"Cancel today's leave before requesting overtime":
            !j.outsideHoursWorkAllowed?"Employer does not allow work while closed":
            !staffWindow(j,s.minute)||!staffWindow(j,s.minute+59)?"Outside the employer's staff access window; wait for opening or choose another activity":
            DailyRoutines.overtimeMinutes(j,a,s.minute)==0?"Scheduled work is available; no extra hours needed":
            !funded(s,a,j)?"Employer has no spare funds for overtime; choose another activity":null;
        boolean approved=approved(s,a,s.buildings.get(j.building),s.minute);
        return new StaffAccessStatus(reason==null,approved,approved?a.workPermit.until:null,j.earlyAccessMinutes,j.lateAccessMinutes,
            j.outsideHoursWorkAllowed,reason==null?"Overtime staff access can be requested; wages are reserved when WORK starts":reason);
    }
    public static String request(State s,Resident a) {
        Job j=s.jobs.get(a.job);Engine.require(j!=null,"Accept a job first");
        StaffAccessStatus status=status(s,a);Engine.require(status.canRequest(),status.reason());
        WorkPermit p=new WorkPermit();p.job=j.id;p.from=s.minute;p.until=Math.min(s.minute+180,windowEnd(j,s.minute));
        a.workPermit=p;
        return "Overtime staff access approved until world minute "+p.until+"; travel to your workplace and choose WORK item overtime. Funding is checked again when work starts";
    }
}
