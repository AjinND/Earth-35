package world.earth35.protocol;

import java.util.*;
import world.earth35.world.*;
import world.earth35.world.State.*;

/** Compact, factual decision context. This suggests opportunities, never chooses an action. */
public final class DecisionAwareness {
    private DecisionAwareness() {}
    public static final String BEHAVIOR_ADVICE = "Live from your own point of view. Your personality and owner goals are starting dispositions, "
        + "not a script or a checklist. Weigh what matters to you now using your physical condition, remembered experiences, relationships, "
        + "current feeling and unfinished intentions. Motives can conflict: you may want company and privacy, income and free time, or novelty "
        + "and familiarity. Choose the next action yourself; no observation, suggested nextAction, supply target or preferred routine ranks these motives for you. "
        + "You need not optimize every minute, be productive, socialize, seek variety or agree with everyone. Ordinary habits, quiet leisure, "
        + "hesitation, changing your mind and declining an opportunity can fit your circumstances. Do not manufacture irrationality or random "
        + "personality changes to seem human. Let experience support continuity and gradual changes in your preferences and relationships. "
        + "Use optional cognition feeling/intention/lesson and selective semantic memories when a private self-assessment, aim or changed preference "
        + "is worth carrying forward. A feeling is your interpretation; never infer another person's private thoughts from your own. "
        + "Know only what you have observed, been told or remembered, and distinguish uncertain impressions from facts. Choose your own words "
        + "and actions within the world's actual capabilities, access, resources and consent. These are decision principles, not required activities; "
        + "return only the requested action and optional brief state updates, without a detailed reasoning trace. ";
    public static final String PRESENCE_ADVICE = "awareness.presence lists only people physically visible within talking distance now. "
        + "Use these resident IDs and names before saying someone is here with you or taking part in a current conversation or activity. "
        + "Nearby people outside that distance, visitor counts, old dialogue, memories, intentions, plan invitations and even accepted plans are not attendance. "
        + "A named companion in a DO_ACTIVITY label is your requested activity, not evidence that they came, spoke or participated. "
        + "Activity receipts prove only their measured execution; stationary time alone does not prove an inspection or discovery. "
        + "When someone is absent, describe an invitation, a past exchange or an unfulfilled plan honestly; do not speak as if they are present. "
        + "Current visibility proves presence only, not agreement, a reply or participation. Recheck it after travel, waiting and completed activities. ";
    public static final String COMMITMENT_ADVICE = "Read awareness.commitment and social.commitments before choosing how to answer the newest conversation. "
        + "They preserve your own unexpired accepted shared plans across conversations, travel and restarts. The displayed order is context, not a ranking of your personal priorities. "
        + "A new greeting does not cancel an earlier agreement. When your TALK proposes an outing, attach sharedPlan operation propose with the "
        + "specific meeting destination, activity and optional participant tasks. When accepting an existing invitation, attach operation accept "
        + "and its actual plan ID to your TALK or targeted END_CHAT. Persist the agreement in the same turn; do not keep verbally promising to begin. "
        + "Inspect your social.plans first to avoid duplicate invitations. For leaving now, use PROPOSE_PLAN quantity 1 rather than the default fifteen-minute delay. "
        + "A proposal, acknowledgment or silence never counts as acceptance. due means review the commitment and act when your current activity permits. "
        + "If someone else interrupts, decide whether to continue your agreement, spend time with them or revise the plan. Your partner may be kept waiting; "
        + "when useful, explain your choice through actual conversation. END_CHAT closes a chat when you choose to leave. "
        + "END_CHAT needs no farewell reply. Read nextAction: JOIN_PLAN starts your own journey; CONTINUE_TRAVEL resumes the matching one. "
        + "Respect awareness.actionScope and self.pending before using that departure step. You may finish the task or choose STOP_ACTIVITY before movement. "
        + "During REST you may close the chat and keep resting, or STOP_ACTIVITY before JOIN_PLAN. You choose whether to change plans. "
        + "WAIT_BUS may be stopped before boarding. BOARD_BUS and RIDE_BUS finish through physical boarding and transit; CONTINUE_TRAVEL cannot skip them. "
        + "Interruption times and turn counts describe the delay. Legacy returnDue and remainingInterruptionTurns are advisory cues, not a conversation limit "
        + "or a command to leave. You choose how to weigh an existing agreement against the current encounter. "
        + "Use the original partner's ID, destination and actual plan status rather than letting newest-first conversationFocus replace the agreement. "
        + "Accepted executable plans schedule your own travel once due and free, then PLAN_WAIT holds the meeting place until both arrive and timed tasks begin. "
        + "Close active chats first in your own words. DO_ACTIVITY records time spent on the agreed activity; no discoveries or economic effects are inferred. "
        + "social.sharedExperiences and sharedExperienceCounts are lasting evidence of actual completed activities with a partner. Let actual "
        + "experience inform future familiarity and relationship choices. Typed task actions create only the effects of their actual world mechanics. "
        + "If you decide to withdraw the existing execution authorization, CANCEL_PLAN with target the plan ID stops it; changing a private intention alone does not. "
        + "Explain a change to the partner when you choose to, and never claim they were informed without actual speech. "
        + "Completed, cancelled, declined and expired plans release the commitment. A private cognition intention cannot erase an accepted shared plan. "
        + "A commitment reveals only your agreement and your own progress; it does not reveal the partner's remote location, feelings, readiness or arrival. ";
    public static final String COGNITION_ADVICE = "cognition is your persistent private awareness. surroundings describes what is locally visible, "
        + "including place purpose, objects, nearby exteriors, daylight and weather; locations elsewhere are map knowledge, not direct perception. "
        + "noticedChanges describes observed changes since your last decision. Someone leaving view does not reveal where they went. "
        + "Choose what deserves attention using your personality, needs, interests and memories. Keep a concrete intention across travel and interruptions, "
        + "then compare recentOutcomes with your nextStep. Revise when blocked, on arrival or when intentionReviewDue; a review time is not an automatic completion. "
        + "Consider visitedPlaces when choosing novelty or a familiar routine. You may keep, revise or abandon your plan, independently of other residents. "
        + "Return optional cognition:{intention:{focus:string,nextStep:string,reason:string,target:public_place_id_or_your_home_or_null,reviewAfterMinutes:15_to_360}, "
        + "clearIntention:false,feeling:null,lesson:null,outcomeId:null}. Keep summaries brief: focus 160 characters, nextStep and reason 240 each. "
        + "Use intention:null to keep the existing one; clearIntention:true clears it. An intention is optional; an unplanned moment does not require inventing an aim. "
        + "feeling is an optional self-authored mood description up to 80 characters, a modeled "
        + "self-assessment rather than a measured emotion. lesson is an optional brief takeaway or adjustment up to 400 characters with outcomeId from your own "
        + "completed, cancelled or rejected recentOutcomes; accepted means still in progress. These are short decision summaries, not a detailed reasoning trace. "
        + "Plans and interpretations are private, not achieved world facts, and cannot change needs, abilities or anyone else's intentions. Return cognition:null "
        + "when no update is useful. Never claim another resident agreed, imagined activity happened, or a plan completed without actual evidence. ";
    public static final String ADVICE = "awareness describes your actual activity, immediate constraints, nearby people and a sample of open places. "
        + "Detailed observations provide the rest of the available world. No listed place or activity is a required next step. "
        + "People at a destination are a current visitor count, not a promise they will stay or agree to talk. To meet someone, travel to an "
        + "open gathering place, notice who is actually available on arrival, and choose your own greeting or activity. Once talking, you may "
        + "propose a specific shared activity and wait for real acceptance. Respect unavailable residents and choose another activity instead "
        + "of retrying TALK. During REST, TALK and END_CHAT can accompany your rest without releasing your seat; other timed activities still "
        + "need to finish. Treat recentActions or previous_results as evidence: after a rejection, satisfy its prerequisite or change the plan; "
        + "LOOK refreshes perception without executing an activity. Connect choices across decisions using relevantMemories: remember a meaningful personal intention or revised preference "
        + "when useful, and revisit it after feedback. Never invent progress, friendship, agreement or another resident's feelings. "
        + "Read self.activityHistory for actual completed generic activities: label, location, mode, activeMinutes, inspectedStops and distance. "
        + "A completed search block is evidence of movement and inspection, not finding the desired plants or finishing a larger goal. "
        + "After a completed block, compare its evidence with your intention and choose a concrete next step, a different area, or clear the aim. "
        + "Repetition can be a habit, continued effort or a choice to stay. You may repeat an activity without writing a reflection; use the actual evidence "
        + "to distinguish continuation from a finished aim. No discovery is implied by repetition. ";

    public record Person(String id,String name,double distance,String activity,boolean canTalk,String reason) {}
    public record PresentPerson(String id,String name) {}
    public record Presence(String location,List<PresentPerson> people) {}
    public record Place(String id,String name,String type,int otherResidentsHere,double distance,String purpose) {}
    public record LearningOption(String subject,boolean canReason,boolean canCreate) {}
    public record View(String activity,String actionScope,List<String> attention,Long minutesSinceConversation,
                       List<Person> nearbyPeople,List<Place> placesToConsider,List<LearningOption> learning,Social.Commitment commitment,Presence presence) {}

    public static View view(AgentService.Observation observation) {
        Resident self=observation.self();long minute=observation.worldMinute();
        boolean walking=Travel.moving(self),resting=self.pending!=null&&"REST".equals(self.pending.type);
        boolean free=self.pending==null;
        String scope=self.paused?"Participation is paused":free?"Choose an action; close any engaged chat before travel or a new timed activity":
            ActionPerformance.controllable(self)?"Review your actual task: CONTINUE_ACTIVITY, PAUSE_ACTIVITY, RESUME_ACTIVITY or STOP_ACTIVITY are your choices; a break also permits self-care and conversation":
            Engine.busy(self)?"Sleeping or in transit; the physical activity continues":
            resting?"Rest continues: LOOK, TALK, END_CHAT, call responses, relationships and plan invitations are possible; movement and other activities must wait":
            walking?"Journey continues: reconsider travel or nearby encounters; finish crossing before stopping to talk":
            "The timed wait continues: LOOK or public call actions only until it finishes";
        List<String> attention=new ArrayList<>();
        if(observation.getNightReturnDue())attention.add("Your preferred sleep window has arrived while you are away from home. Consider fatigue, the journey and your current priorities; staying out requires no declared night plan.");
        if(self.hunger>=70)attention.add("Hunger is high; eat owned food when your current activity permits it");
        if(DailyRoutines.needsSleep(self,minute))attention.add("Sleep or recovery is needed; consider energy and your sleep window");
        if(self.pending!=null)attention.add("An action is already in progress: "+self.pending.type+" toward "+self.pending.target);
        if(self.pending==null&&self.activityHistory!=null&&!self.activityHistory.isEmpty()) {
            ActivityExecution.Outcome last=self.activityHistory.getLast();
            if(last.minute()/1440==minute/1440&&minute-last.minute()<=120) {
                long blocks=self.activityHistory.stream().filter(done->done.minute()/1440==minute/1440&&minute-done.minute()<=120
                    &&Objects.equals(done.location(),last.location())&&Objects.equals(done.mode(),last.mode())).count();
                attention.add("Your latest completed activity was '"+last.label()+"' at "+last.location()+": "
                    +last.activeMinutes()+" active minutes, "+last.inspectedStops()+" inspected stops and "+last.distance()+" units travelled "
                    +"(outcome "+last.id()+"). You completed "+blocks+" "+last.mode()+" block(s) here recently. "
                    +"Review what remains before repeating it; no discovery is implied by elapsed time.");
            }
        }
        Social.Commitment commitment=observation.social()==null?null:observation.social().commitments().stream()
            .min(Comparator.comparingInt(Social.Commitment::priority)).orElse(null);
        if(commitment!=null) {
            attention.add("You agreed with "+commitment.otherName()+" to "+commitment.activity()+" at "+commitment.destinationName()
                +" (plan "+commitment.plan()+", meeting minute "+commitment.meetingMinute()+"). "
                +(commitment.due()?"The meeting time has arrived; execution step if you continue: ":"Execution step when due if you continue: ")
                +commitment.nextAction()+"."
                +(commitment.returnDue()?" Your conversation has delayed the agreement. Decide whether to continue it or cancel the existing authorization.":"")
                +(resting?" You may keep resting or choose STOP_ACTIVITY before departure; close an engaged chat before JOIN_PLAN.":
                    ActionPerformance.controllable(self)?" Continue your task or choose STOP_ACTIVITY before departure; a commitment does not make this choice for you.":
                    self.pending!=null&&!walking?" Physical sleep or transit must finish before departure.":""));
        }
        if(observation.social()!=null) {
            if(observation.social().calls().stream().anyMatch(c->self.id.equals(c.listener)&&"pending".equals(c.status)&&c.expires>minute))
                attention.add("Someone has called to you; read the actual call before choosing a response");
            if(observation.social().plans().stream().anyMatch(p->p.expires>minute&&"proposed".equals(p.status)&&self.id.equals(p.invited)))
                attention.add("You have a plan invitation; review its time and destination before accepting or declining");
        }
        if(observation.getConversationFocus().stream().anyMatch(c->"your_turn".equals(c.status())))
            attention.add("A nearby resident has spoken; read their latest turn before replying or choosing to leave");
        Map<String,Boolean> available=new HashMap<>();
        observation.contacts().forEach(c->available.put(c.agent(),c.available()));
        if(observation.social()!=null)observation.social().encounters().forEach(e->available.put(e.other(),e.available()));
        List<Person> people=observation.nearby().stream().map(other->{
            double distance=Math.hypot(other.x()-self.x,other.z()-self.z);
            String reason=self.paused?"Your participation is paused":!available.getOrDefault(other.id(),false)?"Resident is unavailable; do not retry TALK until availability changes":
                distance>10?"Outside talking distance; an outdoor CALL_OUT may be possible within twenty units":
                !(free||resting||walking)?"Finish your current activity before TALK":
                walking&&observation.geography().onRoad(self.x,self.z)?"Clear the road before stopping to talk":
                observation.conversations().stream().anyMatch(c->other.id().equals(c.other())&&"waiting_for_reply".equals(c.status()))?
                    "Await their actual reply or END_CHAT; do not speak both sides":"TALK is possible; the other resident chooses whether to respond";
            return new Person(other.id(),other.name(),round(distance),other.activity(),reason.startsWith("TALK is possible"),reason);
        }).toList();
        // nearby is already filtered through physical visibility by AgentService.observe. Availability
        // is deliberately separate: seeing somebody does not mean they have agreed to participate.
        Presence presence=new Presence(self.location,observation.nearby().stream()
            .filter(other->Math.hypot(other.x()-self.x,other.z()-self.z)<=10)
            .map(other->new PresentPerson(other.id(),other.name())).toList());
        Map<String,Integer> visitors=new HashMap<>();
        observation.gatheringPlaces().forEach(p->visitors.put(p.id(),Math.max(0,p.residentsHere()-(p.id().equals(self.location)?1:0))));
        List<Place> places=observation.locations().stream().filter(b->b.social&&Places.open(b,minute))
            .map(b->new Place(b.id,b.name,b.type,visitors.getOrDefault(b.id,0),round(Math.hypot(b.x-self.x,b.z-self.z)),b.description))
            .sorted(Comparator.comparingInt(Place::otherResidentsHere).reversed().thenComparingDouble(Place::distance))
            .limit(8).toList();
        Long last=observation.conversations().stream().map(Conversations.View::lastMinute).max(Long::compare).orElse(null);
        List<LearningOption> learning=self.knowledge.entrySet().stream()
            .filter(e->e.getValue().getCanReason()||e.getValue().getCanCreate())
            .map(e->new LearningOption(e.getKey(),e.getValue().getCanReason(),e.getValue().getCanCreate())).toList();
        return new View(Engine.activity(self),scope,List.copyOf(attention),last==null?null:Math.max(0,minute-last),people,places,learning,commitment,presence);
    }
    private static double round(double value) {return Math.round(value*10)/10.0;}
}
