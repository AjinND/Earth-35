package world.earth35.world;

import java.util.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

/** Public physical evidence, and resident-controlled participation, shared by every action. */
public final class ActionPerformance {
    private ActionPerformance() {}
    @io.swagger.v3.oas.annotations.media.Schema(name="ActionPerformance")
    public record View(String actionId,String action,String phase,String posture,String gesture,
                       String heldItem,String task,Double heading,boolean anchored,int activeMinutes) {}
    private static final Set<String> CONTROLLABLE=Set.of("WORK","REST","READ_BOOK","DO_ACTIVITY",
        "RESEARCH_PROJECT","BUILD_PROJECT","PRODUCE_PRODUCT","BUILD_CONSTRUCTION","PLACE_OBJECT","MOVE_OBJECT","WAIT_RAIN","PLAN_WAIT","WAIT_BUS");
    public static boolean controllable(Resident a) {return a!=null&&a.pending!=null&&CONTROLLABLE.contains(a.pending.type);}
    public static boolean awake(Resident a) {return a!=null&&(a.pending==null||!Set.of("SLEEP","BOARD_BUS","RIDE_BUS").contains(a.pending.type));}
    public static boolean control(ActionType action) {return Set.of(ActionType.CONTINUE_ACTIVITY,ActionType.PAUSE_ACTIVITY,ActionType.RESUME_ACTIVITY,ActionType.STOP_ACTIVITY).contains(action);}
    public static boolean selfCare(ActionType action) {return Set.of(ActionType.EAT,ActionType.USE_PRODUCT,ActionType.WEAR,ActionType.REMOVE_CLOTHING).contains(action);}
    public static String control(State s,Resident a,Command command,Engine.Effects e) {
        Engine.require(controllable(a),"No controllable activity is in progress; use travel controls for a journey");
        Pending p=a.pending;
        Engine.require(!Engine.present(command.target())||command.target().equals(p.id),"Target must be your current activity action ID");
        switch(command.action()) {
            case CONTINUE_ACTIVITY -> {Engine.require(!p.activityPaused,"Resume the activity before continuing");return "Continuing the chosen activity; existing progress retained";}
            case PAUSE_ACTIVITY -> {
                Engine.require(!p.activityPaused,"Activity is already paused");p.activityPaused=true;p.activityPauseMinute=s.minute;
                e.facts.add(new Engine.Fact("activity",a.id,p.target,a.name+" took a break from "+p.type+"."));return "Activity paused; no work, learning or completion time will be credited during the break";
            }
            case RESUME_ACTIVITY -> {
                Engine.require(p.activityPaused,"Activity is already active");
                Engine.require(!Conversations.active(s,a),"Finish your conversation with END_CHAT before resuming");
                p.activityPaused=false;p.activityPauseMinute=-1;p.lastWorkedMinute=s.minute;
                e.facts.add(new Engine.Fact("activity",a.id,p.target,a.name+" resumed "+p.type+"."));return "Activity resumed from actual retained progress";
            }
            case STOP_ACTIVITY -> {stop(s,a,e,"Resident chose to stop");return "Activity stopped; completed knowledge and earned wages retained, unused reservations released";}
            default -> throw new Engine.Invalid("Unknown activity control");
        }
    }
    public static void stop(State s,Resident a,Engine.Effects e,String reason) {
        Pending p=a.pending;if(p==null)return;
        switch(p.type) {
            case "WORK" -> Employment.stopWork(s,a,e,reason);
            case "BUILD_CONSTRUCTION" -> Construction.interrupt(s,a,e,reason);
            case "RESEARCH_PROJECT","BUILD_PROJECT" -> Projects.interrupt(s,a,e,reason);
            case "PRODUCE_PRODUCT" -> Ventures.interrupt(s,a,e,reason);
            case "PLACE_OBJECT","MOVE_OBJECT" -> WorldObjects.interrupt(s,a,e,reason);
            case "DO_ACTIVITY" -> ActivityExecution.cancel(a,p,e,reason);
            default -> {e.completions.add(new Engine.Completion(p.id,"cancelled",reason+"; executed progress retained"));a.pending=null;}
        }
    }
    /** A break holds progress, never wall-clock access, needs, wages, materials, or venue closing. */
    public static boolean beforeTick(State s,Resident a,Engine.Effects e) {
        Pending p=a.pending;
        if(p==null)return false;
        if(a.paused&&Set.of("WORK","RESEARCH_PROJECT","BUILD_PROJECT","PRODUCE_PRODUCT","BUILD_CONSTRUCTION","PLACE_OBJECT","MOVE_OBJECT").contains(p.type)) {stop(s,a,e,"Participation paused");return true;}
        if(a.paused&&"READ_BOOK".equals(p.type)){p.startedAt++;p.until++;return true;}
        if("WORK".equals(p.type)&&!p.trackedWork) {
            Job job=s.jobs.get(a.job);
            p.trackedWork=true;p.workDurationMinutes=WorkPayroll.duration(p);
            p.regularWorkMinutes=Math.min(p.workMinutes,Math.max(0,p.workDurationMinutes-p.overtimeMinutes));
            if(job!=null){p.workHourlyWage=job.hourlyWage;p.workOvertimeHourlyWage=DailyRoutines.overtimeHourlyWage(job);p.workEmployer=Economy.employer(job);}
        }
        if("WORK".equals(p.type)&&p.trackedWork) {
            Job job=s.jobs.get(a.job);Building b=job==null?null:s.buildings.get(job.building);
            if(job==null||!Objects.equals(a.location,p.target)||a.energy<=0||a.hunger>=98) {
                stop(s,a,e,"Work interrupted by actual access or physical needs");return true;
            }
            if(s.minute>p.until||(p.activityPaused||!p.workspaceReady)&&s.minute>=p.until) {Employment.stopWork(s,a,e,"Chosen work block ended");return true;}
        }
        if(!p.activityPaused)return false;
        if("BUILD_CONSTRUCTION".equals(p.type)) {
            Construction.Site site=s.construction.get(p.target);int time=Math.floorMod(s.minute-1,1440);
            if(site==null||!"building".equals(site.status)||time<Construction.START_MINUTE||time>=Construction.END_MINUTE){stop(s,a,e,"Construction access ended during the break");return true;}
        }
        // Keep duration specifications fixed while skipping domain work. Job blocks still end on schedule.
        if(!Set.of("WORK","WAIT_BUS").contains(p.type)){p.startedAt++;p.until++;}
        p.lastWorkedMinute=s.minute;
        if(p.activityExecution!=null)p.activityExecution.lastMinute=s.minute;
        return true;
    }
    public static void accepted(State s,Resident a,Command command,String id) {
        boolean speech=Set.of(ActionType.TALK,ActionType.END_CHAT,ActionType.CALL_OUT,ActionType.ACKNOWLEDGE,ActionType.DECLINE_CALL).contains(command.action());
        if(a.pending!=null&&!speech&&!selfCare(command.action())){a.recentPerformance=null;return;}
        String gesture=switch(command.action()) {
            case EAT -> "coffee".equals(command.item())?"drink":"eat";
            case TALK,END_CHAT,CALL_OUT -> "talk";
            case ACKNOWLEDGE,DECLINE_CALL -> Engine.present(command.message())?"talk":command.action()==ActionType.ACKNOWLEDGE?"observe":"idle";
            case LOOK -> "observe";
            default -> "idle";
        };
        // Abstract transactions and private thought have no modeled visible physical action.
        if("idle".equals(gesture)&&!selfCare(command.action())){a.recentPerformance=null;return;}
        String held=command.action()==ActionType.EAT?("coffee".equals(command.item())?"coffee":"meal"):null;
        Resident other=command.target()==null?null:s.agents.get(command.target());
        if(Set.of(ActionType.TALK,ActionType.END_CHAT).contains(command.action())&&other!=null){a.facingHeading=Math.atan2(other.x-a.x,other.z-a.z);a.facingUntil=s.minute+2;}
        a.recentPerformance=new View(id,command.action().name(),"perform",Seating.seated(a)||Workspaces.seated(a)?"seated":"standing",gesture,held,command.action().name(),a.facingHeading,!Travel.moving(a),0);
        a.performanceUntil=s.minute+1;
    }
    public static View view(Resident a) {
        Pending p=a.pending;if(p==null)return a.recentPerformance;
        if(a.recentPerformance!=null&&(Set.of("talk","listen").contains(a.recentPerformance.gesture())||p.activityPaused)&&(p.activityPaused||Set.of("REST","PLAN_WAIT").contains(p.type)||p.socialWith!=null))return a.recentPerformance;
        String phase=p.activityPaused?"paused":Engine.present(p.waitingReason)?"blocked":"perform";
        String posture="SLEEP".equals(p.type)?"lying":Seating.seated(a)||Workspaces.seated(a)?"seated":"standing";
        String task=p.detail==null?p.type:p.detail;Double heading=a.facingHeading;
        if("WORK".equals(p.type))task=a.occupation;
        String gesture=switch(p.type) {
            case "JUMP" -> "jump";
            case "READ_BOOK" -> "read";
            case "WORK" -> "work";
            case "BUILD_CONSTRUCTION","BUILD_PROJECT" -> "build";
            case "RESEARCH_PROJECT" -> "research";
            case "PRODUCE_PRODUCT" -> "produce";
            case "PLACE_OBJECT","MOVE_OBJECT" -> "place";
            case "DO_ACTIVITY" -> p.activityExecution!=null&&Set.of("observe","search").contains(p.activityExecution.mode)?"observe":"idle";
            default -> "idle";
        };
        if("RESEARCH_PROJECT".equals(p.type))task=p.projectMethod+" research";
        if(Travel.moving(a)||"DO_ACTIVITY".equals(p.type)&&p.activityExecution!=null&&"travel".equals(p.activityExecution.phase))phase="approach";
        if(Seating.seatIndex(a)!=null&&!p.seatReady)phase="approach";
        if("BUILD_CONSTRUCTION".equals(p.type)) {
            ConstructionWork.View work=ConstructionWork.view(a);
            if(work!=null){phase=work.phase();task=work.task();heading=work.heading();}
        }
        if(Set.of("PLACE_OBJECT","MOVE_OBJECT").contains(p.type)) {
            WorldObjects.WorkView work=WorldObjects.workView(a);
            if(work!=null){phase=work.phase();task=work.task();heading=work.heading();}
        }
        Workspaces.View workspace=Workspaces.view(a);
        if(workspace!=null){phase=workspace.phase();task=workspace.task();heading=workspace.heading();}
        if(p.activityPaused)phase="paused";
        String held="perform".equals(phase)?switch(p.type){case "READ_BOOK"->"book";case "BUILD_CONSTRUCTION"->"hammer";default->null;}:null;
        if(!"perform".equals(phase))gesture="idle";
        int active=switch(p.type){case "READ_BOOK"->p.readingMinutes;case "DO_ACTIVITY"->p.activityExecution==null?0:p.activityExecution.activeMinutes;default->Math.max(p.workMinutes,p.activeMinutes);};
        return new View(p.id,p.type,phase,posture,gesture,held,task,heading,!Travel.moving(a),active);
    }
}
