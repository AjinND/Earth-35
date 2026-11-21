package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class ConversationPlanTest {
    private Resident resident(State s,String id) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location="park";
        a.x=s.buildings.get("park").x;a.z=s.buildings.get("park").z;s.agents.put(id,a);return a;
    }
    private SharedPlanInput proposal(String destination,List<PlanTaskInput> tasks) {
        return new SharedPlanInput("propose",null,destination,"Search for purple coneflowers and black-eyed Susans",1,15,tasks);
    }
    private SharedPlanInput response(String operation,String id) {
        return new SharedPlanInput(operation,id,null,null,null,null,null);
    }
    private Engine.Effects act(State s,Resident actor,Resident other,ActionType type,String message,SharedPlanInput plan,String id) {
        Engine.Effects effects=new Engine.Effects();
        Engine.apply(s,actor,new Command(id,type,other==null?null:other.id,message,null,null,null,null,null,plan),id,effects);
        return effects;
    }
    private Engine.Effects talk(State s,Resident actor,Resident other,String message,SharedPlanInput plan,String id) {
        return act(s,actor,other,ActionType.TALK,message,plan,id);
    }
    private Conversation conversation(State s,Resident a,Resident b) {
        return s.conversations.get(Conversations.key(a.id,b.id));
    }

    @Test void concreteDialogueCreatesAProposalAndIndependentReplyAcceptsTheSavedAssignments() {
        State s=State.prototypeSeed();Resident diego=resident(s,"diego"),mei=resident(s,"mei");
        List<PlanTaskInput> assignments=List.of(
            new PlanTaskInput(diego.id,"park","Search the trailhead for flowers",20),
            new PlanTaskInput(mei.id,"courtyard","Search the open areas for flowers",25));
        var proposed=talk(s,diego,mei,"Shall we search for flowers together? I'll check the trailhead and you the open areas.",
            proposal("park",assignments),"flower-search");
        SocialPlan plan=s.socialPlans.get("flower-search");
        assertEquals("proposed",plan.status);assertTrue(plan.meetingMinute>=s.minute+1);
        assertEquals("park",plan.tasks.get(diego.id).destination());assertEquals("courtyard",plan.tasks.get(mei.id).destination());
        assertEquals(20,plan.tasks.get(diego.id).durationMinutes());assertEquals(25,plan.tasks.get(mei.id).durationMinutes());
        assertEquals(Conversations.key(diego.id,mei.id),plan.conversationKey);
        assertEquals(1L,plan.sourceTurnSequence);assertEquals(s.minute,plan.sourceMinute);
        assertNull(diego.pending);assertNull(mei.pending,"A proposal does not author the invitee's movement or agreement");
        assertEquals(1,conversation(s,diego,mei).turns.size());
        assertTrue(proposed.facts.stream().anyMatch(f->f.kind().equals("conversation")));
        assertTrue(proposed.facts.stream().anyMatch(f->f.kind().equals("social_plan")));
        var originalTasks=new LinkedHashMap<>(plan.tasks);
        talk(s,mei,diego,"Let's begin. I'll search the open areas while you check the trailhead.",response("accept",plan.id),"accept-search");
        assertEquals("accepted",plan.status);assertEquals(originalTasks,plan.tasks);
        assertEquals(2,conversation(s,diego,mei).turns.size());assertNull(diego.pending);assertNull(mei.pending);
        assertEquals(plan.id,Social.commitments(s,diego).getFirst().plan());
    }

    @Test void unannotatedSpeechNeverManufacturesAnAgreementOrASyntheticReply() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        talk(s,a,b,"Shall we begin searching together?",null,"invite");
        talk(s,b,a,"Let's begin!",null,"reply");
        assertTrue(s.socialPlans.isEmpty());assertEquals(2,conversation(s,a,b).turns.size());
        assertNull(a.pending);assertNull(b.pending);
    }

    @Test void omittedTaskAssignmentsCreateTheSameExplicitActivityForBothParticipants() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        talk(s,a,b,"Let's search the park for flowers.",proposal("park",null),"search");
        SocialPlan p=s.socialPlans.get("search");
        assertEquals(Set.of(a.id,b.id),p.tasks.keySet());
        for(PlanTask task:p.tasks.values()) {
            assertEquals("park",task.destination());assertEquals(p.activity,task.activity());assertEquals(15,task.durationMinutes());
        }
    }

    @Test void invalidSecondAssignmentLeavesNoDialogueInvitationOrOtherPartialChange() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        for(List<PlanTaskInput> tasks:List.of(
            List.of(new PlanTaskInput(a.id,"park","Search the trailhead",15),new PlanTaskInput(a.id,"park","Search open land",15)),
            List.of(new PlanTaskInput(a.id,"park","Search the trailhead",15),new PlanTaskInput("outsider","park","Search open land",15)),
            List.of(new PlanTaskInput(a.id,"park","Search the trailhead",15),new PlanTaskInput(b.id,"apt-101","Search open land",15)),
            List.of(new PlanTaskInput(a.id,"park","Search the trailhead",15),new PlanTaskInput(b.id,"park","Search open land",101)))) {
            Engine.Effects effects=new Engine.Effects();
            Command command=new Command("invalid",ActionType.TALK,b.id,"Shall we begin?",null,null,null,null,null,proposal("park",tasks));
            assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,command,"invalid",effects));
            assertTrue(s.conversations.isEmpty());assertTrue(s.socialPlans.isEmpty());assertTrue(effects.facts.isEmpty());
            assertTrue(a.encounterReviewed.isEmpty());assertNull(a.pending);assertNull(b.pending);
        }
    }

    @Test void onlyTheInvitedConversationPartnerCanAcceptAndAcceptanceCannotRewriteTasks() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b"),outsider=resident(s,"outsider");
        talk(s,a,b,"Shall we search together?",proposal("park",null),"search");
        SocialPlan plan=s.socialPlans.get("search");var tasks=new LinkedHashMap<>(plan.tasks);
        assertThrows(Engine.Invalid.class,()->talk(s,a,b,"I accept for us both.",response("accept",plan.id),"self-accept"));
        assertThrows(Engine.Invalid.class,()->talk(s,outsider,a,"I accept your invitation.",response("accept",plan.id),"foreign-accept"));
        assertThrows(Engine.Invalid.class,()->talk(s,b,outsider,"I accept.",response("accept",plan.id),"wrong-partner"));
        SharedPlanInput changed=new SharedPlanInput("accept",plan.id,"courtyard",null,null,null,null);
        assertThrows(Engine.Invalid.class,()->talk(s,b,a,"Let's go elsewhere instead.",changed,"changed-accept"));
        assertEquals("proposed",plan.status);assertEquals(tasks,plan.tasks);assertEquals(1,conversation(s,a,b).turns.size());
        assertEquals(1,s.conversations.size());
    }

    @Test void duplicateActiveProposalAndRepeatedAcceptanceAreRejectedBeforeAnotherTurn() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        talk(s,a,b,"Shall we search together?",proposal("park",null),"search");
        assertThrows(Engine.Invalid.class,()->talk(s,b,a,"Let's make another invitation.",proposal("courtyard",null),"duplicate"));
        assertEquals(1,s.socialPlans.size());assertEquals(1,conversation(s,a,b).turns.size());
        talk(s,b,a,"Yes, let's search.",response("accept","search"),"accepted");
        assertThrows(Engine.Invalid.class,()->talk(s,b,a,"I accept again.",response("accept","search"),"reaccepted"));
        assertEquals(2,conversation(s,a,b).turns.size());assertEquals("accepted",s.socialPlans.get("search").status);
    }

    @Test void anActivityInProgressRejectsADuplicateInvitationBeforeRecordingSpeech() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        talk(s,a,b,"Shall we search together?",proposal("park",null),"search");
        talk(s,b,a,"Yes, let's search.",response("accept","search"),"accept");
        SocialPlan current=s.socialPlans.get("search");current.status="in_progress";
        Engine.Effects effects=new Engine.Effects();
        Command duplicate=new Command("duplicate",ActionType.TALK,b.id,"Let's make another invitation.",null,null,null,null,null,proposal("courtyard",null));
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,duplicate,"duplicate",effects));
        assertEquals(2,conversation(s,a,b).turns.size());assertEquals(1,s.socialPlans.size());assertTrue(effects.facts.isEmpty());
        assertEquals("in_progress",current.status);
    }

    @Test void farewellCanAcceptOrCancelThroughActualAuthoredWords() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        talk(s,a,b,"Shall we search together?",proposal("park",null),"search");
        act(s,b,a,ActionType.END_CHAT,"Yes, I'll join you. See you there.",response("accept","search"),"accept-farewell");
        assertEquals("accepted",s.socialPlans.get("search").status);assertTrue(conversation(s,a,b).closed);
        act(s,a,b,ActionType.END_CHAT,"I need to change my plans. We'll search another day. Take care.",response("cancel","search"),"cancel-farewell");
        assertEquals("cancelled",s.socialPlans.get("search").status);assertEquals(3,conversation(s,a,b).turns.size());
        assertTrue(Social.commitments(s,a).isEmpty());
    }

    @Test void declinedProposalDoesNotBecomeACommitment() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        talk(s,a,b,"Shall we search together?",proposal("park",null),"search");
        talk(s,b,a,"I need to rest today.",response("decline","search"),"decline");
        assertEquals("declined",s.socialPlans.get("search").status);assertTrue(Social.commitments(s,a).isEmpty());
        assertNull(a.pending);assertNull(b.pending);
    }

    @Test void unsupportedOrAmbiguousAnnotationsNeverRecordDialogue() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        for(SharedPlanInput input:List.of(
            new SharedPlanInput("agree",null,"park","Search for flowers",1,15,null),
            new SharedPlanInput("propose",null,"park","",1,15,null),
            new SharedPlanInput("propose",null,"park","Search for flowers",0,15,null),
            new SharedPlanInput("propose",null,"park","Search for flowers",1,0,null),
            new SharedPlanInput("propose",null,"point:900000,900000","Search for flowers",1,15,null))) {
            assertThrows(Engine.Invalid.class,()->talk(s,a,b,"Shall we begin?",input,"invalid"));
            assertTrue(s.conversations.isEmpty());assertTrue(s.socialPlans.isEmpty());
        }
        assertThrows(Engine.Invalid.class,()->act(s,a,null,ActionType.END_CHAT,"Let's search together.",proposal("park",null),"ambiguous"));
        assertThrows(Engine.Invalid.class,()->act(s,a,b,ActionType.LOOK,null,proposal("park",null),"wrong-action"));
        assertTrue(s.conversations.isEmpty());assertTrue(s.socialPlans.isEmpty());
    }

    @Test void conversationEvidenceAndParticipantAssignmentsSurvivePersistence() throws Exception {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        talk(s,a,b,"Shall we search together?",proposal("park",List.of(
            new PlanTaskInput(a.id,"park","Search the trailhead",20),new PlanTaskInput(b.id,"courtyard","Search open land",25))),"search");
        talk(s,b,a,"Yes, let's begin.",response("accept","search"),"accept");
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);
        SocialPlan saved=restored.socialPlans.get("search");
        assertEquals("accepted",saved.status);assertEquals(s.socialPlans.get("search").tasks,saved.tasks);
        assertEquals(Conversations.key(a.id,b.id),saved.conversationKey);assertEquals(1L,saved.sourceTurnSequence);
        assertEquals(s.minute,saved.sourceMinute);
    }

    @Test void sharedPlansCanAssignTwoActualDryIslandPointsOutsideNamedVenues() {
        State s=new State();s.geographyVersion=2;
        Resident a=new Resident(),b=new Resident();a.id="a";a.name="A";b.id="b";b.name="B";
        a.x=b.x=-390;a.z=b.z=-280;a.location=b.location="point:-390,-280";
        s.agents.put(a.id,a);s.agents.put(b.id,b);
        String trailhead="point:-390,-275",openArea="point:-385,-280";
        assertNotNull(Travel.destination(s,trailhead));assertNotNull(Travel.destination(s,openArea));
        talk(s,a,b,"I'll search this trailhead while you check the clearing.",proposal(trailhead,List.of(
            new PlanTaskInput(a.id,trailhead,"Search the trailhead",20),
            new PlanTaskInput(b.id,openArea,"Search the open clearing",20))),"outdoor-search");
        talk(s,b,a,"Agreed. I'll check the clearing.",response("accept","outdoor-search"),"agree-outdoors");
        SocialPlan plan=s.socialPlans.get("outdoor-search");assertEquals("accepted",plan.status);
        assertEquals(trailhead,plan.tasks.get(a.id).destination());assertEquals(openArea,plan.tasks.get(b.id).destination());
        assertNull(a.pending);assertNull(b.pending);
    }

    @Test void unsupportedExecutableTasksAreRejectedBeforeSpeechOrAnyResourceChange() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        long money=a.money;var inventory=new LinkedHashMap<>(a.inventory);
        for(ActionType action:List.of(ActionType.BUY,ActionType.TRANSFER,ActionType.ACCEPT_PROJECT,ActionType.USE_PRODUCT,ActionType.MOVE)) {
            SharedPlanInput input=proposal("park",List.of(
                new PlanTaskInput(a.id,"park","Perform the promised task",15,action,"untrusted-target",null),
                new PlanTaskInput(b.id,"park","Search the park",15)));
            Engine.Effects effects=new Engine.Effects();
            Command command=new Command("invalid",ActionType.TALK,b.id,"Shall we do this together?",null,null,null,null,null,input);
            assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,command,"invalid",effects));
            assertTrue(s.conversations.isEmpty());assertTrue(s.socialPlans.isEmpty());assertTrue(effects.facts.isEmpty());
            assertEquals(money,a.money);assertEquals(inventory,a.inventory);
        }
    }

    @Test void genuineResearchTaskMetadataPersistsWithoutInventingMembershipOrFindings() throws Exception {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        Projects.Project project=new Projects.Project();project.id="flower-study";project.title="Study garden conditions";
        project.location="studio";s.projects.put(project.id,project);
        SharedPlanInput input=proposal("park",List.of(
            new PlanTaskInput(a.id,"studio","Observe actual conditions for our research",30,ActionType.RESEARCH_PROJECT,project.id,"observation"),
            new PlanTaskInput(b.id,"park","Search the flower beds",20)));
        talk(s,a,b,"I'll research the conditions at the studio while you search the flower beds.",input,"research-plan");
        talk(s,b,a,"Agreed. I'll search the flower beds.",response("accept","research-plan"),"accept-research");
        PlanTask task=s.socialPlans.get("research-plan").tasks.get(a.id);
        assertEquals("RESEARCH_PROJECT",task.action());assertEquals(project.id,task.target());assertEquals("observation",task.item());
        assertEquals("studio",task.destination());assertEquals(30,task.durationMinutes());
        assertTrue(project.members.isEmpty());assertTrue(project.research.isEmpty());assertNull(a.pending);assertNull(b.pending);
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);
        assertEquals(task,restored.socialPlans.get("research-plan").tasks.get(a.id));
    }

    @Test void typedTasksRequireExistingTargetsAndValidResearchMethodsBeforeDialogue() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        Projects.Project project=new Projects.Project();project.id="research";project.location="studio";s.projects.put(project.id,project);
        for(PlanTaskInput task:List.of(
            new PlanTaskInput(a.id,"studio","Research current conditions",30,ActionType.RESEARCH_PROJECT,null,"observation"),
            new PlanTaskInput(a.id,"studio","Research current conditions",30,ActionType.RESEARCH_PROJECT,"unknown","observation"),
            new PlanTaskInput(a.id,"studio","Research current conditions",30,ActionType.RESEARCH_PROJECT,project.id,"imagined"),
            new PlanTaskInput(a.id,"studio","Research current conditions",15,ActionType.RESEARCH_PROJECT,project.id,"observation"),
            new PlanTaskInput(a.id,"park","Read a real book",30,ActionType.READ_BOOK,"invented-book",null))) {
            SharedPlanInput input=proposal("park",List.of(task,new PlanTaskInput(b.id,"park","Search the flower beds",20)));
            assertThrows(Engine.Invalid.class,()->talk(s,a,b,"Shall we begin?",input,"invalid"));
            assertTrue(s.conversations.isEmpty());assertTrue(s.socialPlans.isEmpty());
        }
    }
}
