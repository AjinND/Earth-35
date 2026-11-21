package world.earth35.world;

import java.util.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

/** Explicit, speaker-authored plan metadata attached to an actual conversation turn. */
public final class ConversationPlans {
    private ConversationPlans() {}
    private static final Set<ActionType> TASK_ACTIONS=Set.of(ActionType.DO_ACTIVITY,ActionType.USE_OBJECT,ActionType.READ_BOOK,
        ActionType.RESEARCH_PROJECT,ActionType.BUILD_PROJECT,ActionType.BUILD_CONSTRUCTION,ActionType.PRODUCE_PRODUCT);
    private static final Set<String> WORKPLACES=Set.of("office","studio","market","workshop","coffee_shop","grocery",
        "restaurant","shop","bakery","fruit-shop","library","community_center");

    /** Validate the entire annotation before dialogue or plan state can change. */
    public static void validate(State s,Resident actor,String target,SharedPlanInput input) {
        if(input==null)return;
        Engine.require(input.operation()!=null&&List.of("propose","accept","decline","cancel").contains(input.operation()),
            "Choose sharedPlan operation propose, accept, decline or cancel");
        Resident other=s.agents.get(target);
        Engine.require(other!=null&&!actor.id.equals(target),"Attach a shared plan to a specific conversation partner");
        Engine.together(actor,other);
        Engine.require(Social.perceivable(s,actor,other),"The plan's conversation partner must be in the same visible space");
        if(input.operation().equals("propose")) {
            Engine.require(input.plan()==null,"A new shared plan cannot replace an existing plan ID");
            Engine.require(Social.conversationAvailable(other),"This resident is unavailable for a plan invitation");
            activity(input.activity());
            int delay=input.delayMinutes()==null?1:input.delayMinutes();
            Engine.require(delay>=1&&delay<=100,"Choose a meeting one to one hundred minutes from now");
            duration(input.durationMinutes()==null?15:input.durationMinutes());
            destination(s,actor,input.destination());
            destination(s,other,input.destination());
            Engine.require(s.socialPlans.values().stream().noneMatch(p->p.expires>s.minute
                &&List.of("proposed","accepted","in_progress").contains(p.status)
                &&Conversations.key(p.proposer,p.invited).equals(Conversations.key(actor.id,other.id))),
                "An active plan already exists with this resident");
            if(input.tasks()!=null&&!input.tasks().isEmpty()) {
                Engine.require(input.tasks().size()==2,"Assign one shared-plan task to each participant");
                Set<String> assigned=new HashSet<>();
                for(PlanTaskInput task:input.tasks()) {
                    Engine.require(task!=null&&task.resident()!=null&&Set.of(actor.id,other.id).contains(task.resident())&&assigned.add(task.resident()),
                        "Assign unique tasks only to the two conversation participants");
                    activity(task.activity());
                    int minutes=task.durationMinutes()==null?input.durationMinutes()==null?15:input.durationMinutes():task.durationMinutes();
                    duration(minutes);
                    ActionType action=taskAction(s,task,minutes);
                    destination(s,s.agents.get(task.resident()),task.destination(),action!=ActionType.DO_ACTIVITY);
                    Building meeting=Travel.destination(s,input.destination());
                    Resident departure=new Resident();departure.id=task.resident();departure.location=meeting.id;
                    departure.x=meeting.x;departure.z=meeting.z;
                    destination(s,departure,task.destination(),action!=ActionType.DO_ACTIVITY);
                }
            }
            return;
        }
        Engine.require(input.plan()!=null&&!input.plan().isBlank(),"Choose the existing shared plan ID");
        Engine.require(input.destination()==null&&input.activity()==null&&input.delayMinutes()==null
            &&input.durationMinutes()==null&&(input.tasks()==null||input.tasks().isEmpty()),
            "Respond to the existing plan without changing assignments; propose a new plan to change them");
        SocialPlan plan=Social.plan(s,actor,input.plan());
        String partner=actor.id.equals(plan.proposer)?plan.invited:plan.proposer;
        Engine.require(other.id.equals(partner),"The shared plan must belong to this conversation's two participants");
        if(!input.operation().equals("cancel"))
            Engine.require(actor.id.equals(plan.invited)&&plan.status.equals("proposed"),
                "Only the invited resident can answer a proposed plan");
    }

    /** Called only after the validated TALK or farewell has been recorded. */
    public static void apply(State s,Resident actor,String target,SharedPlanInput input,String actionId,Engine.Effects effects) {
        if(input==null)return;
        switch(input.operation()) {
            case "propose" -> {
                Social.propose(s,actor,target,input.destination(),input.activity(),input.delayMinutes()==null?1:input.delayMinutes(),actionId,effects);
                SocialPlan plan=s.socialPlans.get(actionId);
                plan.tasks.clear();
                if(input.tasks()==null||input.tasks().isEmpty()) {
                    int duration=input.durationMinutes()==null?15:input.durationMinutes();
                    for(String resident:List.of(actor.id,target))
                        plan.tasks.put(resident,new PlanTask(resident,input.destination(),input.activity().strip(),duration));
                } else for(PlanTaskInput task:input.tasks()) {
                    int duration=task.durationMinutes()==null?input.durationMinutes()==null?15:input.durationMinutes():task.durationMinutes();
                    ActionType action=task.action()==null?ActionType.DO_ACTIVITY:task.action();
                    plan.tasks.put(task.resident(),new PlanTask(task.resident(),task.destination(),task.activity().strip(),duration,
                        action.name(),task.target(),task.item()));
                }
                Conversation conversation=s.conversations.get(Conversations.key(actor.id,target));
                plan.conversationKey=Conversations.key(actor.id,target);
                plan.sourceTurnSequence=conversation.turnSequence;
                plan.sourceMinute=s.minute;
            }
            case "accept" -> Social.respond(s,actor,input.plan(),true,effects);
            case "decline" -> Social.respond(s,actor,input.plan(),false,effects);
            case "cancel" -> Social.cancel(s,actor,input.plan(),effects);
            default -> throw new Engine.Invalid("Unsupported shared plan operation");
        }
    }

    private static void activity(String activity) {
        Engine.require(activity!=null&&!activity.isBlank()&&activity.length()<=1000,
            "Describe the shared-plan activity in one to one thousand characters");
    }
    private static void duration(int minutes) {
        Engine.require(minutes>=1&&minutes<=100,"Choose an activity duration of one to one hundred minutes");
    }
    private static ActionType taskAction(State s,PlanTaskInput task,int minutes) {
        ActionType action=task.action()==null?ActionType.DO_ACTIVITY:task.action();
        Engine.require(TASK_ACTIONS.contains(action),"Choose a supported activity action for the shared plan");
        Engine.require(task.target()==null||!task.target().isBlank()&&task.target().length()<=80,"Use a valid activity target of up to eighty characters");
        Engine.require(task.item()==null||task.item().length()<=80,"Use an activity item of up to eighty characters");
        Engine.require(action==ActionType.DO_ACTIVITY||task.target()!=null,"A typed shared-plan activity needs its existing action target");
        switch(action) {
            case DO_ACTIVITY -> Engine.require(task.target()==null||task.target().equals(task.destination()),
                "A general shared activity takes place at its assigned destination");
            case USE_OBJECT -> Engine.require(Objects.equals(task.destination(),Engine.objectLocation(s,task.target())),
                "Choose an existing seating object at the assigned destination");
            case READ_BOOK -> Engine.require(BookCatalog.books().stream().anyMatch(book->book.id().equals(task.target())),
                "Choose an existing book from the library catalog");
            case RESEARCH_PROJECT, BUILD_PROJECT -> {
                Projects.Project project=s.projects.get(task.target());
                Engine.require(project!=null&&Objects.equals(task.destination(),project.location),
                    "Choose an existing project at the assigned workplace");
                if(action==ActionType.RESEARCH_PROJECT) {
                    Engine.require(task.item()!=null&&List.of("observation","test","economics").contains(task.item()),
                        "Choose observation, test or economics for the planned research");
                    Engine.require(minutes>=30,"Planned research requires at least thirty minutes");
                }
            }
            case BUILD_CONSTRUCTION -> {
                Engine.require(s.construction.containsKey(task.target())&&s.buildings.containsKey(task.target()),
                    "Choose an existing construction site for the shared activity");
                Building destination=Travel.destination(s,task.destination());
                Places.Entrance entrance=Places.entrance(s.buildings.get(task.target()));
                Engine.require(destination!=null&&!Places.enclosed(destination)&&Math.hypot(destination.x-entrance.x(),destination.z-entrance.z())<=2,
                    "Assign construction work to its reachable outdoor entrance");
            }
            case PRODUCE_PRODUCT -> {
                Ventures.Business business=s.businesses.get(task.target());
                Engine.require(business!=null&&Objects.equals(task.destination(),business.location),
                    "Choose an existing business at the assigned workplace");
            }
            default -> throw new Engine.Invalid("Unsupported shared-plan activity");
        }
        return action;
    }
    private static void destination(State s,Resident resident,String target) {
        destination(s,resident,target,false);
    }
    private static void destination(State s,Resident resident,String target,boolean workplace) {
        Building building=Travel.destination(s,target);
        Engine.require(building!=null&&(target.startsWith("point:")||building.social||workplace&&WORKPLACES.contains(building.type)),
            "Choose a public gathering place or reachable dry island point for the shared plan");
        if(target.startsWith("point:")) {
            Engine.require(s.buildings.values().stream().noneMatch(other->Places.enclosed(other)
                &&Math.abs(building.x-other.x)<3.5&&Math.abs(building.z-other.z)<3.5),
                "Choose plan destinations outside building footprints");
            Engine.require(s.worldObjects.values().stream().noneMatch(object->Math.abs(building.x-object.x)<1&&Math.abs(building.z-object.z)<1),
                "Choose plan destinations clear of sign footprints");
        }
        try {Travel.path(s,resident,building,Travel.defaultRoute(resident));}
        catch(IllegalStateException invalid) {throw new Engine.Invalid("No available route to the shared-plan destination");}
    }
}
