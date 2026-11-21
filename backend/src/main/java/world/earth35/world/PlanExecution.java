package world.earth35.world;

import java.nio.charset.StandardCharsets;
import java.util.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

/** Executes only the steps each resident has actually proposed or accepted. */
public final class PlanExecution {
    private PlanExecution() {}
    private static final Set<ActionType> TASK_ACTIONS=Set.of(ActionType.DO_ACTIVITY,ActionType.USE_OBJECT,ActionType.READ_BOOK,
        ActionType.RESEARCH_PROJECT,ActionType.BUILD_PROJECT,ActionType.BUILD_CONSTRUCTION,ActionType.PRODUCE_PRODUCT);

    static String actionId(SocialPlan plan,String resident,String stage) {
        return "plan-"+UUID.nameUUIDFromBytes((plan.id+"\n"+resident+"\n"+stage).getBytes(StandardCharsets.UTF_8));
    }

    /** Matching resident-authored actions fulfill the agreement too; never execute them a second time. */
    public static void attachAction(State s,Resident resident,Command command,String actionId) {
        if(command==null||!TASK_ACTIONS.contains(command.action()))return;
        try {
            SocialPlan plan=s.socialPlans.values().stream().filter(p->"in_progress".equals(p.status)&&p.expires>s.minute
                &&(resident.id.equals(p.proposer)||resident.id.equals(p.invited)))
                .sorted(Comparator.comparingLong((SocialPlan p)->p.meetingMinute).thenComparing(p->p.id)).findFirst().orElse(null);
            if(plan==null||plan.tasks==null||plan.finished==null||plan.finished.contains(resident.id)
                ||plan.activityActions==null||plan.activityUntil==null||plan.blocked==null||plan.retryAfter==null)return;
            PlanTask task=plan.tasks.get(resident.id);
            if(task==null||!Objects.equals(task.destination(),resident.location)||taskAction(task)!=command.action())return;
            String target=task.target()==null?task.destination():task.target();
            String actualTarget=command.action()==ActionType.DO_ACTIVITY&&(command.target()==null||command.target().isBlank())?
                resident.location:command.target();
            if(!Objects.equals(target,actualTarget)||!Objects.equals(task.item(),command.item()))return;
            if(command.action()==ActionType.DO_ACTIVITY&&(task.activity()==null||command.message()==null
                ||!task.activity().strip().equals(command.message().strip())))return;
            Pending pending=resident.pending;
            if(pending!=null&&(!Objects.equals(pending.id,actionId)||pending.until-s.minute<taskMinutes(task)))return;
            if(pending!=null&&!plan.activityActions.containsKey(resident.id))plan.expires=Math.max(plan.expires,pending.until+60);
            if(pending!=null)pending.socialPlanId=plan.id;
            plan.activityActions.put(resident.id,actionId);plan.activityUntil.put(resident.id,pending==null?s.minute:pending.until);
            if(pending==null)plan.finished.add(resident.id);
            plan.blocked.remove(resident.id);plan.retryAfter.remove(resident.id);
        } catch(RuntimeException invalidMetadata) {
            // The original action already succeeded. Invalid saved plan metadata
            // must never turn that real action into a rejection or duplicate it.
        }
    }

    static void tick(State s,Engine.Effects effects) {
        List<SocialPlan> plans=s.socialPlans.values().stream()
            .filter(p->!p.tasks.isEmpty()&&List.of("accepted","in_progress").contains(p.status))
            .sorted(Comparator.comparingLong((SocialPlan p)->p.meetingMinute).thenComparing(p->p.id)).toList();
        for(SocialPlan plan:plans) {
            if("in_progress".equals(plan.status))recordCompletions(s,plan,effects);
            if(plan.finished.containsAll(plan.tasks.keySet())) {
                plan.status="completed";plan.completedMinute=s.minute;
                sharedExperience(s,plan);
                Relationships.record(s,"plan:"+plan.id+":completed","shared_activity",plan.proposer,plan.invited,
                    "Both participants completed their agreed tasks for "+plan.activity+" at "+Social.destinationName(s,plan.destination)+".");
                release(s,plan,effects,"Shared plan completed");
                effects.facts.add(new Engine.Fact("social_plan",plan.proposer,plan.invited,
                    "The agreed activity is complete: "+plan.activity+". Each participant finished their own task."));
                continue;
            }
            if("in_progress".equals(plan.status)) {
                String interruption=interruption(s,plan,effects);
                if(interruption!=null) {
                    plan.status="cancelled";
                    Relationships.record(s,"plan:"+plan.id+":interrupted","plan_interrupted",plan.proposer,plan.invited,
                        "The agreed activity "+plan.activity+" was interrupted: "+interruption+". Shared completion was not recorded.");
                    plan.retryAfter.clear();
                    release(s,plan,effects,"Agreed activity interrupted: "+interruption);
                    effects.facts.add(new Engine.Fact("social_plan",plan.proposer,plan.invited,
                        "The agreed activity was interrupted: "+plan.activity+". "+interruption
                            +". Completed individual tasks remain recorded; the shared plan was not completed. Each resident can choose their next action."));
                    continue;
                }
            }
            if(s.minute>=plan.expires)continue;
            if("accepted".equals(plan.status)) {
                Resident proposer=s.agents.get(plan.proposer),invited=s.agents.get(plan.invited);
                if(s.minute>=plan.meetingMinute&&meetingReady(s,proposer,plan)&&meetingReady(s,invited,plan)) {
                    if(!begin(s,plan,effects))continue;
                } else {
                    joinOrWait(s,plan,proposer,effects);joinOrWait(s,plan,invited,effects);
                    continue;
                }
            }
            for(PlanTask task:plan.tasks.values()) {
                Resident resident=s.agents.get(task.resident());
                if(resident==null||plan.finished.contains(resident.id)||!ready(s,resident)||!primary(s,resident,plan))continue;
                if(!task.destination().equals(resident.location)) {
                    start(s,plan,resident,ActionType.MOVE,task.destination(),null,null,"task-travel",effects);
                } else {
                    try {
                        ActionType action=taskAction(task);
                        Integer quantity=switch(action) {
                            case PRODUCE_PRODUCT->1;
                            case BUILD_PROJECT,BUILD_CONSTRUCTION,USE_OBJECT->null;
                            case RESEARCH_PROJECT->Math.max(30,task.durationMinutes());
                            default->task.durationMinutes();
                        };
                        start(s,plan,resident,action,task.target()==null?task.destination():task.target(),task.activity(),task.item(),quantity,"activity",effects);
                    } catch(Engine.Invalid invalid) {blocked(s,plan,resident,invalid.getMessage(),effects);}
                }
            }
        }
    }

    private static boolean ready(State s,Resident resident) {
        return !resident.paused&&resident.pending==null&&!Conversations.active(s,resident);
    }
    private static boolean primary(State s,Resident resident,SocialPlan plan) {
        return Social.commitments(s,resident).stream().findFirst().map(c->c.plan().equals(plan.id)).orElse(false);
    }
    private static boolean meetingReady(State s,Resident resident,SocialPlan plan) {
        return resident!=null&&!resident.paused&&!Conversations.active(s,resident)&&plan.destination.equals(resident.location)
            &&(resident.pending==null||"PLAN_WAIT".equals(resident.pending.type)&&plan.id.equals(resident.pending.socialPlanId))
            &&primary(s,resident,plan);
    }
    private static void joinOrWait(State s,SocialPlan plan,Resident resident,Engine.Effects effects) {
        if(resident==null||resident.paused||!primary(s,resident,plan))return;
        if(plan.destination.equals(resident.location)) {
            if(ready(s,resident)) {
                start(s,plan,resident,ActionType.WAIT_PLAN,plan.id,null,null,"wait",effects);
            } else if(resident.pending!=null&&"PLAN_WAIT".equals(resident.pending.type)&&plan.id.equals(resident.pending.socialPlanId)) {
                resident.pending.until=plan.expires;
            }
            return;
        }
        if(!ready(s,resident))return;
        var primary=Social.commitments(s,resident).stream().findFirst().orElse(null);
        if(primary==null||!primary.plan().equals(plan.id)||!primary.due())return;
        start(s,plan,resident,ActionType.JOIN_PLAN,plan.id,null,null,"join",effects);
    }
    private static boolean begin(State s,SocialPlan plan,Engine.Effects effects) {
        long deadline=s.minute;
        for(PlanTask task:plan.tasks.values()) {
            Resident resident=s.agents.get(task.resident());
            if(resident!=null) {
                if(s.minute<plan.retryAfter.getOrDefault(resident.id,0L))return false;
                try {deadline=Math.max(deadline,s.minute+Social.walkingMinutes(s,resident,task.destination())+taskMinutes(task)+60);}
                catch(Engine.Invalid|IllegalStateException blocked) {blocked(s,plan,resident,blocked.getMessage(),effects);return false;}
            }
        }
        plan.status="in_progress";plan.startedMinute=s.minute;
        for(String id:List.of(plan.proposer,plan.invited))clearWait(s.agents.get(id),plan,effects);
        plan.expires=Math.max(plan.expires,deadline);
        effects.facts.add(new Engine.Fact("social_plan",plan.proposer,plan.invited,
            s.agents.get(plan.proposer).name+" and "+s.agents.get(plan.invited).name+" met at "+Social.destinationName(s,plan.destination)
                +" and began their agreed activity: "+plan.activity));
        return true;
    }
    private static void clearWait(Resident resident,SocialPlan plan,Engine.Effects effects) {
        if(resident.pending!=null&&"PLAN_WAIT".equals(resident.pending.type)&&plan.id.equals(resident.pending.socialPlanId))
            Engine.finish(resident,effects,"Agreed meeting began");
    }
    private static void recordCompletions(State s,SocialPlan plan,Engine.Effects effects) {
        for(PlanTask task:plan.tasks.values()) {
            String id=plan.activityActions.get(task.resident());
            if(id!=null&&s.minute>=plan.activityUntil.getOrDefault(task.resident(),Long.MAX_VALUE)
                &&effects.completions.stream().anyMatch(c->id.equals(c.id())&&"completed".equals(c.status())))
                plan.finished.add(task.resident());
        }
    }
    private static String interruption(State s,SocialPlan plan,Engine.Effects effects) {
        for(PlanTask task:plan.tasks.values()) {
            if(plan.finished.contains(task.resident()))continue;
            String action=plan.activityActions.get(task.resident());
            if(action==null)continue; // A step that could not start can still wait for its actual constraint to clear.
            Engine.Completion cancelled=effects.completions.stream()
                .filter(c->action.equals(c.id())&&"cancelled".equals(c.status())).findFirst().orElse(null);
            Resident resident=s.agents.get(task.resident());
            String name=resident==null?task.resident():resident.name;
            if(cancelled!=null)return name+"'s agreed task stopped: "+cancelled.result();
            if(resident==null||resident.pending==null||!action.equals(resident.pending.id))
                return name+" is no longer carrying out their agreed task";
        }
        return null;
    }
    private static void start(State s,SocialPlan plan,Resident resident,ActionType action,String target,String detail,
                              Integer duration,String stage,Engine.Effects effects) {
        start(s,plan,resident,action,target,detail,null,duration,stage,effects);
    }
    private static void start(State s,SocialPlan plan,Resident resident,ActionType action,String target,String detail,String item,
                              Integer duration,String stage,Engine.Effects effects) {
        if(s.minute<plan.retryAfter.getOrDefault(resident.id,0L))return;
        String key=resident.id+":"+stage;int attempt=plan.attempts.getOrDefault(key,0);
        String id=actionId(plan,resident.id,stage+":"+attempt);
        Command command=new Command(id,action,target,detail,item,duration,null);
        try {
            String result=Engine.apply(s,resident,command,id,effects);
            if(resident.pending!=null&&id.equals(resident.pending.id))resident.pending.socialPlanId=plan.id;
            if(stage.equals("activity")) {
                plan.activityActions.put(resident.id,id);
                plan.activityUntil.put(resident.id,resident.pending==null?s.minute:resident.pending.until);
                if(resident.pending==null)plan.finished.add(resident.id);
            }
            plan.attempts.put(key,attempt+1);plan.blocked.remove(resident.id);plan.retryAfter.remove(resident.id);
            effects.starts.add(new Engine.Started(resident.id,command,id,result));
        } catch(Engine.Invalid|IllegalStateException blocked) {
            // Current opening or route constraints remain authoritative. Never
            // replace another activity or pretend a blocked step happened.
            blocked(s,plan,resident,blocked.getMessage(),effects);
        }
    }
    private static void blocked(State s,SocialPlan plan,Resident resident,String reason,Engine.Effects effects) {
        plan.retryAfter.put(resident.id,s.minute+15);
        if(!Objects.equals(plan.blocked.put(resident.id,reason),reason))
            effects.facts.add(new Engine.Fact("social_plan",resident.id,plan.id,
                resident.name+" could not start a step of the agreed plan: "+reason));
    }
    private static ActionType taskAction(PlanTask task) {
        ActionType action;
        try {action=task.action()==null?ActionType.DO_ACTIVITY:ActionType.valueOf(task.action());}
        catch(IllegalArgumentException invalid) {throw new Engine.Invalid("Unsupported agreed task action");}
        Engine.require(TASK_ACTIONS.contains(action),"Unsupported agreed task action");return action;
    }
    private static int taskMinutes(PlanTask task) {
        return switch(taskAction(task)) {
            case BUILD_PROJECT,BUILD_CONSTRUCTION->60;
            case USE_OBJECT,PRODUCE_PRODUCT->30;
            case RESEARCH_PROJECT->Math.max(30,task.durationMinutes());
            default->task.durationMinutes();
        };
    }
    static void release(State s,SocialPlan plan,Engine.Effects effects,String reason) {
        for(String id:List.of(plan.proposer,plan.invited)) {
            Resident resident=s.agents.get(id);
            if(resident!=null&&resident.pending!=null&&plan.id.equals(resident.pending.socialPlanId)) {
                switch(resident.pending.type) {
                    case "RESEARCH_PROJECT","BUILD_PROJECT"->Projects.interrupt(s,resident,effects,reason);
                    case "PRODUCE_PRODUCT"->Ventures.interrupt(s,resident,effects,reason);
                    case "BUILD_CONSTRUCTION"->Construction.interrupt(s,resident,effects,reason);
                    default->{effects.completions.add(new Engine.Completion(resident.pending.id,"cancelled",reason));resident.pending=null;}
                }
            }
        }
    }
    private static void sharedExperience(State s,SocialPlan plan) {
        for(String id:List.of(plan.proposer,plan.invited)) {
            Resident resident=s.agents.get(id);if(resident==null)continue;
            String other=id.equals(plan.proposer)?plan.invited:plan.proposer;
            resident.sharedExperiences.add(new SocialExperience(plan.id,other,plan.destination,plan.activity,plan.startedMinute,s.minute));
            while(resident.sharedExperiences.size()>20)resident.sharedExperiences.removeFirst();
            resident.sharedExperienceCounts.merge(other,1,Integer::sum);
        }
    }
}
