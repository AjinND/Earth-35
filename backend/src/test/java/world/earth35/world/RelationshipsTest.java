package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class RelationshipsTest {
    private final ObjectMapper json=new ObjectMapper();
    private Resident resident(State state,String id) {
        Resident resident=new Resident();resident.id=id;resident.name=id;resident.home="apt-101";
        resident.location="park";resident.x=state.buildings.get("park").x;resident.z=state.buildings.get("park").z;
        state.agents.put(id,resident);return resident;
    }
    private Relationships.Draft draft(String other,String evidence,String label,String feeling,Integer warmth,Integer trust,Integer tension) {
        return new Relationships.Draft(other,label,feeling,"Their actual reply made me feel understood, though I am still cautious",
            List.of(evidence),warmth,trust,70,25,null,tension);
    }
    private String record(State state,String id,String actor,String other) {
        Relationships.record(state,id,"conversation",actor,other,"They discussed a difficult day and listened to each other");
        return Relationships.views(state.agents.get(actor),state.minute).stream().filter(v->v.other().equals(other))
            .findFirst().orElseThrow().evidence().getLast().id();
    }
    private SocialPlan acceptedActivity(State state,Resident alex,Resident sam) {
        Social.propose(state,alex,sam.id,"park","Quietly observe the park",1,"shared-observation",new Engine.Effects());
        SocialPlan plan=state.socialPlans.get("shared-observation");
        plan.tasks.put(alex.id,new PlanTask(alex.id,"park","Quietly observe the park",2));
        plan.tasks.put(sam.id,new PlanTask(sam.id,"park","Quietly observe the park",2));
        Social.respond(state,sam,plan.id,true,new Engine.Effects());
        while(state.minute<plan.meetingMinute)Engine.tick(state);
        assertEquals("in_progress",plan.status);return plan;
    }
    @Test void factualInteractionsCreateFamiliarityWithoutInventingFeelingsOrReciprocity() {
        State state=State.prototypeSeed();Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        String evidence=record(state,"exchange-1",alex.id,sam.id);
        var own=Relationships.views(alex,state.minute).getFirst();var other=Relationships.views(sam,state.minute).getFirst();
        assertEquals(1,own.interactionCount());assertEquals(1,other.interactionCount());
        assertTrue(own.needsReflection());assertNull(own.appraisal());assertNull(other.appraisal());
        assertTrue(alex.relationships.isEmpty());assertTrue(sam.relationships.isEmpty());
        Relationships.update(state,alex,draft(sam.id,evidence,"New acquaintance","Warm but wary",75,20,45));
        var feeling=Relationships.views(alex,state.minute).getFirst().appraisal();
        assertEquals("Warm but wary",feeling.feeling());assertEquals(75,feeling.warmth());
        assertEquals(20,feeling.trust());assertEquals(45,feeling.tension());assertNull(feeling.attraction());
        assertEquals("New acquaintance",alex.relationships.get(sam.id));assertTrue(sam.relationships.isEmpty());
        assertNull(Relationships.views(sam,state.minute).getFirst().appraisal());
    }
    @Test void eachResidentCanInterpretTheSameEventDifferentlyAndTimeDoesNotRewriteTheirFeelings() {
        State state=State.prototypeSeed();Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        String evidence=record(state,"exchange-1",alex.id,sam.id);
        Relationships.update(state,alex,draft(sam.id,evidence,"Interesting acquaintance","Curious and hopeful",70,30,15));
        Relationships.update(state,sam,draft(alex.id,evidence,"Someone I am cautious around","Uncomfortable but respectful",20,10,65));
        var first=Relationships.views(alex,state.minute).getFirst();var second=Relationships.views(sam,state.minute).getFirst();
        assertFalse(first.needsReflection());assertFalse(second.needsReflection());
        assertNotEquals(first.appraisal().feeling(),second.appraisal().feeling());
        state.minute+=1440*7;
        var later=Relationships.views(alex,state.minute).getFirst();
        assertEquals(first.appraisal(),later.appraisal());assertEquals(1440*7,later.minutesSinceInteraction());
        record(state,"exchange-2",sam.id,alex.id);
        assertTrue(Relationships.views(alex,state.minute).getFirst().needsReflection());
        assertEquals(first.appraisal(),Relationships.views(alex,state.minute).getFirst().appraisal());
    }
    @Test void optionalDimensionsRemainUnknownUntilAuthoredAndLaterUpdatesPreserveThem() {
        State state=State.prototypeSeed();Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        String evidence=record(state,"exchange-1",alex.id,sam.id);
        Relationships.update(state,alex,new Relationships.Draft(sam.id,"Acquaintance","Interested","Their reply gave me something to consider",
            List.of(evidence),null,null,null,null,null,null));
        var initial=Relationships.views(alex,state.minute).getFirst().appraisal();
        assertNull(initial.warmth());assertNull(initial.trust());assertNull(initial.attraction());
        Relationships.update(state,alex,draft(sam.id,evidence,"Acquaintance","Fond but cautious",75,30,35));
        Relationships.update(state,alex,new Relationships.Draft(sam.id,null,"Still fond, with a new concern","I reconsidered their earlier reply",
            List.of(evidence),null,15,null,null,null,50));
        var changed=Relationships.views(alex,state.minute).getFirst();
        assertEquals("Acquaintance",changed.label());assertEquals(75,changed.appraisal().warmth());
        assertEquals(15,changed.appraisal().trust());assertEquals(70,changed.appraisal().respect());
        assertNull(changed.appraisal().attraction());assertEquals(50,changed.appraisal().tension());
        assertEquals(1,changed.interactionCount());
    }
    @Test void unrelatedOrInventedEvidenceAndInvalidDimensionsLeaveTheEntireAppraisalUnchanged() throws Exception {
        State state=State.prototypeSeed();Resident alex=resident(state,"alex"),sam=resident(state,"sam"),maya=resident(state,"maya");
        String evidence=record(state,"own-exchange",alex.id,sam.id);
        Relationships.update(state,alex,draft(sam.id,evidence,"Acquaintance","Cautiously optimistic",60,35,20));
        String unrelated=record(state,"unrelated-exchange",alex.id,maya.id);
        String before=json.writeValueAsString(alex);
        for(String invalid:List.of("invented-event",unrelated)) {
            assertThrows(Engine.Invalid.class,()->Relationships.update(state,alex,draft(sam.id,invalid,"Best friend","Absolutely certain",100,100,0)));
            assertEquals(before,json.writeValueAsString(alex));
        }
        for(int invalid:List.of(-101,101)) {
            assertThrows(Engine.Invalid.class,()->Relationships.update(state,alex,draft(sam.id,evidence,"Best friend","Absolutely certain",invalid,80,0)));
            assertEquals(before,json.writeValueAsString(alex));
        }
        assertThrows(Engine.Invalid.class,()->Relationships.update(state,alex,draft(alex.id,evidence,"Self","Content",50,50,0)));
        assertEquals(before,json.writeValueAsString(alex));
    }
    @Test void invalidRelationshipAnnotationCannotPartiallyChangeTheResidentsMind() throws Exception {
        State state=State.prototypeSeed();Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        record(state,"real-exchange",alex.id,sam.id);alex.mind.feeling="Thoughtful";
        Cognition.update(state,alex,new Cognition.Update(new Cognition.IntentionDraft("Read","Visit the library","I want to learn","library",30),false,null,null,null));
        String before=json.writeValueAsString(alex);
        var invalid=new Cognition.Update(null,true,"Delighted",null,null,draft(sam.id,"invented","Best friend","Certain",100,100,0));
        assertThrows(Engine.Invalid.class,()->Cognition.update(state,alex,invalid));
        assertEquals(before,json.writeValueAsString(alex));
    }
    @Test void sleepingAndPausedResidentsCannotBeGivenAnAuthoredAppraisal() {
        State state=State.prototypeSeed();Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        String evidence=record(state,"exchange-1",alex.id,sam.id);
        var appraisal=draft(sam.id,evidence,"Acquaintance","Thoughtful",50,30,10);
        alex.paused=true;assertThrows(Engine.Invalid.class,()->Relationships.update(state,alex,appraisal));
        alex.paused=false;alex.pending=Engine.pending("sleep","SLEEP",alex.home,state.minute+60);
        assertThrows(Engine.Invalid.class,()->Relationships.update(state,alex,appraisal));
        assertNull(Relationships.views(alex,state.minute).getFirst().appraisal());assertTrue(alex.relationships.isEmpty());
        alex.pending=null;Relationships.update(state,alex,appraisal);
        assertNotNull(Relationships.views(alex,state.minute).getFirst().appraisal());
    }
    @Test void evidenceIsIdempotentAndHistoryStaysBoundedWithoutLosingTheBasisOfEarlierFeelings() throws Exception {
        State state=State.prototypeSeed();Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        String first=record(state,"exchange-0",alex.id,sam.id);
        record(state,"exchange-0",alex.id,sam.id);
        assertEquals(1,Relationships.views(alex,state.minute).getFirst().interactionCount());
        Relationships.update(state,alex,draft(sam.id,first,"Acquaintance","Uncertain",50,20,30));
        for(int i=1;i<80;i++) {
            state.minute++;
            String evidence=record(state,"exchange-"+i,alex.id,sam.id);
            Relationships.update(state,alex,draft(sam.id,evidence,"Growing acquaintance","Interested, with reservations",55,25,25));
        }
        var view=Relationships.views(alex,state.minute).getFirst();
        assertEquals(80,view.interactionCount());assertTrue(view.evidence().size()<80);assertTrue(view.history().size()<80);
        assertFalse(view.history().isEmpty());assertTrue(view.history().stream().allMatch(h->!h.evidence().isEmpty()));
        assertTrue(view.history().stream().allMatch(h->h.evidence().stream().allMatch(e->h.appraisal().evidence().contains(e.id()))));
        State restored=json.readValue(json.writeValueAsString(state),State.class);
        assertEquals(view,Relationships.views(restored.agents.get(alex.id),restored.minute).getFirst());
    }
    @Test void reviewingAllEvidenceOfferedInTheContextClearsTheOptionalReflectionCue() {
        State state=State.prototypeSeed();Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        for(int i=0;i<8;i++) {state.minute++;record(state,"exchange-"+i,alex.id,sam.id);}
        var offered=Relationships.views(alex,state.minute).getFirst();assertTrue(offered.needsReflection());
        var evidence=offered.evidence().stream().map(Relationships.Evidence::id).toList();
        Relationships.update(state,alex,new Relationships.Draft(sam.id,"Acquaintance","Interested but cautious",
            "I considered the recent exchanges available to me",evidence,60,30,65,20,null,25));
        assertFalse(Relationships.views(alex,state.minute).getFirst().needsReflection());
        state.minute++;record(state,"new-exchange",sam.id,alex.id);
        assertTrue(Relationships.views(alex,state.minute).getFirst().needsReflection());
    }
    @Test void aMeaningfulEarlierExperienceCanBeReinterpretedFromItsRetainedReflectionSnapshot() {
        State state=State.prototypeSeed();Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        String original=record(state,"meaningful-exchange",alex.id,sam.id);
        Relationships.update(state,alex,draft(sam.id,original,"Acquaintance","Initially reassured",65,40,15));
        var source=Relationships.views(alex,state.minute).getFirst().history().getFirst().evidence().getFirst();
        for(int i=0;i<Relationships.EVIDENCE_LIMIT+1;i++) {state.minute++;record(state,"later-exchange-"+i,alex.id,sam.id);}
        assertTrue(alex.bonds.get(sam.id).evidence.stream().noneMatch(e->e.id().equals(original)));
        var before=Relationships.views(alex,state.minute).getFirst();
        assertTrue(before.history().getFirst().evidence().contains(source));
        Relationships.update(state,alex,new Relationships.Draft(sam.id,null,"Grateful, but less certain than before",
            "I reconsidered the meaning of the earlier exchange",List.of(original),null,25,null,null,null,30));
        var after=Relationships.views(alex,state.minute).getFirst();
        assertEquals(before.interactionCount(),after.interactionCount());
        assertEquals(List.of(original),after.appraisal().evidence());assertEquals(25,after.appraisal().trust());
        assertEquals(List.of(source),after.history().getLast().evidence());
        assertEquals("Grateful, but less certain than before",after.appraisal().feeling());
    }
    @Test void olderPrivateLabelsLoadWithoutInventedEvidenceAndNewBondsPersist() throws Exception {
        Resident legacy=json.readValue("{\"id\":\"legacy\",\"relationships\":{\"sam\":\"Old friend\"}}",Resident.class);
        assertNotNull(legacy.bonds);
        var view=Relationships.views(legacy,720).getFirst();
        assertEquals("sam",view.other());assertEquals("Old friend",view.label());assertEquals(0,view.interactionCount());
        assertNull(view.appraisal());assertTrue(view.evidence().isEmpty());assertTrue(view.history().isEmpty());
        Resident empty=json.readValue("{\"id\":\"newcomer\"}",Resident.class);
        assertTrue(Relationships.views(empty,720).isEmpty());
    }
    @Test void realConversationAndFarewellSupplyEvidenceWhileRejectedSpeechAndObservationDoNot() {
        State state=State.prototypeSeed();Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        Engine.Effects effects=new Engine.Effects();
        Engine.apply(state,alex,new Command("hello",ActionType.TALK,sam.id,"How has your day been?",null,null,null),"hello",effects);
        long spoken=Relationships.views(alex,state.minute).getFirst().interactionCount();assertTrue(spoken>0);
        assertEquals(spoken,Relationships.views(sam,state.minute).getFirst().interactionCount());
        assertThrows(Engine.Invalid.class,()->Engine.apply(state,alex,new Command("again",ActionType.TALK,sam.id,"How has your day been?",null,null,null),"again",new Engine.Effects()));
        assertEquals(spoken,Relationships.views(alex,state.minute).getFirst().interactionCount());
        Engine.apply(state,alex,new Command("look",ActionType.LOOK,null,null,null,null,null),"look",new Engine.Effects());
        assertEquals(spoken,Relationships.views(alex,state.minute).getFirst().interactionCount());
        Engine.apply(state,alex,new Command("bye",ActionType.END_CHAT,sam.id,"I need to go now. Take care.",null,null,null),"bye",effects);
        assertTrue(Relationships.views(alex,state.minute).getFirst().interactionCount()>spoken);
        assertNull(Relationships.views(alex,state.minute).getFirst().appraisal());
        assertTrue(alex.relationships.isEmpty());assertTrue(sam.relationships.isEmpty());
    }
    @Test void giftsAndDeclinedPlansSupplyFactsWithoutPurchasingWarmthOrInventingRejection() {
        State state=State.prototypeSeed();Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        long alexMoney=alex.money,samMoney=sam.money;
        Engine.apply(state,alex,new Command("gift",ActionType.TRANSFER,sam.id,null,null,null,500L),"gift",new Engine.Effects());
        assertEquals(alexMoney-500,alex.money);assertEquals(samMoney+500,sam.money);
        assertEquals("transfer",Relationships.views(alex,state.minute).getFirst().evidence().getLast().kind());
        Social.propose(state,alex,sam.id,"park","Take a quiet walk",15,"invitation",new Engine.Effects());
        Social.respond(state,sam,"invitation",false,new Engine.Effects());
        var own=Relationships.views(alex,state.minute).getFirst();var other=Relationships.views(sam,state.minute).getFirst();
        assertTrue(own.evidence().stream().anyMatch(e->e.kind().equals("plan_declined")));
        assertEquals(own.interactionCount(),other.interactionCount());
        assertNull(own.appraisal());assertNull(other.appraisal());
        assertTrue(alex.relationships.isEmpty());assertTrue(sam.relationships.isEmpty());
    }
    @Test void silentPublicCallDeclinesAndIgnoringNeverExposeAnotherResidentsPrivateChoice() {
        for(boolean decline:List.of(true,false)) {
            State state=State.prototypeSeed();Resident alex=resident(state,"alex"),sam=resident(state,"sam");
            PublicEncounters.callOut(state,alex,sam.id,"Would you like to talk?","call",new Engine.Effects());
            long heard=Relationships.views(alex,state.minute).getFirst().interactionCount();
            Engine.Effects response=new Engine.Effects();
            sam.x+=50;
            if(decline)PublicEncounters.decline(state,sam,alex.id,null,response);else PublicEncounters.ignore(state,sam);
            assertTrue(response.facts.isEmpty());assertEquals("pending",PublicEncounters.calls(state,alex).getFirst().status);
            assertEquals(heard,Relationships.views(alex,state.minute).getFirst().interactionCount());
            assertEquals(heard,Relationships.views(sam,state.minute).getFirst().interactionCount());
            assertTrue(Relationships.views(alex,state.minute).getFirst().evidence().stream().noneMatch(e->e.kind().equals("public_call_response")));
            state.minute+=11;PublicEncounters.tick(state);
            assertEquals("expired",PublicEncounters.calls(state,alex).getFirst().status);
            assertEquals(heard,Relationships.views(alex,state.minute).getFirst().interactionCount());
        }
    }
    @Test void completedSharedActivitiesRecordActualJointSuccessWithNoInventedSpeakerOrFeelings() {
        State state=State.prototypeSeed();Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        SocialPlan plan=acceptedActivity(state,alex,sam);
        assertTrue(Relationships.views(alex,state.minute).getFirst().evidence().stream().noneMatch(e->e.kind().equals("shared_activity")));
        for(int i=0;i<100&&plan.status.equals("in_progress");i++)Engine.tick(state);
        assertEquals("completed",plan.status);assertEquals(Set.of(alex.id,sam.id),plan.finished);
        var completed=Relationships.views(alex,state.minute).getFirst().evidence().stream().filter(e->e.kind().equals("shared_activity")).toList();
        assertEquals(1,completed.size());assertNull(completed.getFirst().actor());
        assertEquals(1,alex.sharedExperienceCounts.get(sam.id));assertEquals(1,sam.sharedExperienceCounts.get(alex.id));
        assertNull(Relationships.views(alex,state.minute).getFirst().appraisal());assertTrue(alex.relationships.isEmpty());assertTrue(sam.relationships.isEmpty());
        Engine.tick(state);
        assertEquals(1,Relationships.views(alex,state.minute).getFirst().evidence().stream().filter(e->e.kind().equals("shared_activity")).count());
    }
    @Test void anInterruptedTaskRecordsAnUnattributedInterruptionWithoutSharedCompletionCredit() {
        State state=State.prototypeSeed();Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        SocialPlan plan=acceptedActivity(state,alex,sam);
        Engine.Effects stopped=new Engine.Effects();
        Engine.apply(state,alex,new Command("stop",ActionType.STOP_ACTIVITY,null,null,null,null,null),"stop",stopped);
        PlanExecution.tick(state,stopped);
        assertEquals("cancelled",plan.status);assertTrue(alex.sharedExperiences.isEmpty());assertTrue(sam.sharedExperiences.isEmpty());
        var evidence=Relationships.views(alex,state.minute).getFirst().evidence();
        assertTrue(evidence.stream().noneMatch(e->e.kind().equals("shared_activity")));
        var interruption=evidence.stream().filter(e->e.kind().equals("plan_interrupted")).findFirst().orElseThrow();
        assertNull(interruption.actor());assertTrue(interruption.description().contains("Shared completion was not recorded"));
        assertNull(Relationships.views(alex,state.minute).getFirst().appraisal());assertTrue(alex.relationships.isEmpty());assertTrue(sam.relationships.isEmpty());
    }
}
