package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class ProjectsTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private Resident resident(State s,String id,boolean advanced) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";place(s,a,"office");s.agents.put(id,a);
        if(advanced)study(a,"business",true);return a;
    }
    private void place(State s,Resident a,String location) {
        a.location=location;Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;
    }
    /** These fixtures represent saved, server-earned study rather than authored knowledge flags. */
    private void study(Resident a,String subject,boolean intermediate) {
        for(BookCatalog.Book b:BookCatalog.books())if(subject.equals(b.subject())&&(b.depth().equals("introductory")||intermediate&&b.depth().equals("intermediate"))) {
            BookStudy study=new BookStudy();study.studyMinutes=b.lessonMinutes()*b.lessons().size();study.readingMinutes=study.studyMinutes;a.bookStudies.put(b.id(),study);
            if(intermediate&&b.depth().equals("intermediate"))break;
        }
        Library.refreshKnowledge(a);
    }
    private Engine.Effects act(State s,Resident a,ActionType type,String target,String item,String message,Integer quantity,String id) {
        Engine.Effects e=new Engine.Effects();Engine.apply(s,a,new Command(id,type,target,message,item,quantity,null),id,e);return e;
    }
    private void ticks(State s,int minutes) {for(int i=0;i<minutes;i++)Engine.tick(s);}
    private Projects.Project start(State s,Resident a,String recipe,String id) throws Exception {
        List<String> concepts=a.knowledge.get("business").concepts.stream().limit(2).toList();
        String reasoningId="reason-"+id,creationId="work-"+id;
        String reasoning=JSON.writeValueAsString(Map.of("problem","How can residents solve current needs with a useful "+recipe+" product "+id+"?",
            "analysis",concepts.get(0)+" "+concepts.get(1)+" Use these principles to observe actual needs, build a small prototype, compare measured results and calculate costs before assuming that anyone will buy it.","concepts",concepts));
        act(s,a,ActionType.REASON,"business",null,reasoning,null,reasoningId);
        String creation=JSON.writeValueAsString(Map.of("title","Useful "+recipe+" "+id,"reasoningId",reasoningId,
            "content","A concrete local "+recipe+" design "+id+" applies this learned principle: "+concepts.getFirst()+". Observe present needs before building, test one usable prototype and compare its real effect with the predicted benefit. Invite interested peers to investigate costs and improve this design."));
        act(s,a,ActionType.CREATE_WORK,"business","product",creation,null,creationId);
        act(s,a,ActionType.START_PROJECT,creationId,recipe,"office",null,id);return s.projects.get(id);
    }
    private Projects.ResearchFinding research(State s,Resident a,Projects.Project p,String method,String id) {
        a.energy=100;a.hunger=30;
        act(s,a,ActionType.RESEARCH_PROJECT,p.id,method,null,30,id);ticks(s,30);assertNull(a.pending);return p.research.get(a.id+":"+method);
    }
    private Projects.Product built(State s,Resident a,Projects.Project p,String id) {
        research(s,a,p,"observation","observe-"+id);
        act(s,a,ActionType.BUILD_PROJECT,p.id,null,null,null,id);ticks(s,60);return s.products.get(p.productId);
    }
    private void meet(State s,Resident a,Resident b) {
        Conversations.speak(s,a,b,"We should compare our interests and the needs in this neighborhood.");
        Conversations.farewell(s,a,b.id,"I will consider that project and talk again later.",new Engine.Effects());
    }
    @Test void freshSatisfiedResidentsHaveNoPersonalProblemsOrGenericLearningSuggestions() {
        State s=State.seed();Resident a=resident(s,"a",false);
        assertTrue(a.knowledge.isEmpty());assertTrue(a.bookLoans.isEmpty());
        assertTrue(Projects.view(s,a).problems().isEmpty());assertTrue(Projects.problems(s,null).isEmpty());
        assertEquals(List.of("community-needs","weather"),Projects.view(s,a).sharedConditions().stream().map(Projects.Problem::kind).toList());
    }
    @Test void eachResidentReceivesOnlyTheirCurrentMeasuredIssuesWhileCityConditionsAreShared() {
        State s=State.seed();Resident hungry=resident(s,"hungry",false),tiredWet=resident(s,"tired-wet",false);
        hungry.hunger=60;tiredWet.energy=35;tiredWet.wetness=40;
        Projects.View hungryView=Projects.view(s,hungry),tiredWetView=Projects.view(s,tiredWet);
        assertEquals(List.of("hunger"),hungryView.problems().stream().map(Projects.Problem::kind).toList());
        assertEquals(Map.of("hunger",60.0),hungryView.problems().getFirst().evidence());
        assertTrue(hungryView.problems().getFirst().description().contains("60.0/100"));
        assertEquals(List.of("low-energy","wetness"),tiredWetView.problems().stream().map(Projects.Problem::kind).toList());
        assertEquals(Map.of("energy",35.0),tiredWetView.problems().get(0).evidence());
        assertEquals(Map.of("wetness",40.0),tiredWetView.problems().get(1).evidence());
        assertTrue(tiredWetView.problems().get(0).description().contains("35.0/100"));
        assertTrue(tiredWetView.problems().get(1).description().contains("40.0/100"));
        assertEquals(hungryView.sharedConditions(),tiredWetView.sharedConditions());
        Map<String,Double> community=hungryView.sharedConditions().getFirst().evidence();
        assertEquals(2.0,community.get("residents"));assertEquals(1.0,community.get("hungryResidents"));
        assertEquals(1.0,community.get("lowEnergyResidents"));assertEquals(1.0,community.get("wetResidents"));
        assertEquals((double)s.buildings.values().stream().mapToLong(b->Math.max(0,b.stock.getOrDefault("meal",0))).sum(),community.get("mealStock"));
        Projects.Problem weather=hungryView.sharedConditions().get(1);
        assertEquals("Current weather is "+Engine.weather(s.minute)+" at "+Engine.temperature(s.minute)+"°C.",weather.description());
        assertEquals((double)Engine.temperature(s.minute),weather.evidence().get("temperatureC"));
    }
    @Test void personalProblemsDisappearWhenNeedsResolveAndEvidenceRetainsMeasuredPrecision() {
        State s=State.seed();Resident a=resident(s,"a",false);
        a.hunger=82.375;a.energy=20.125;a.wetness=67.875;
        List<Projects.Problem> problems=Projects.problems(s,a);
        assertEquals(3,problems.size());assertEquals(82.375,problems.getFirst().evidence().get("hunger"));
        assertTrue(problems.getFirst().description().contains("82.38/100"));
        a.hunger=59.99;a.energy=35.01;a.wetness=39.99;
        assertTrue(Projects.view(s,a).problems().isEmpty());
        Map<String,Double> community=Projects.view(s,a).sharedConditions().getFirst().evidence();
        assertEquals(0.0,community.get("hungryResidents"));assertEquals(0.0,community.get("lowEnergyResidents"));assertEquals(0.0,community.get("wetResidents"));
    }
    @Test void overdueBookProblemMatchesActualLoanRestrictionsAndClearsAfterReturn() {
        State s=State.seed();Resident reader=resident(s,"reader",false),other=resident(s,"other",false);s.minute=540;place(s,reader,"library");
        Library.borrow(s,reader,"garden-basics",new Engine.Effects());long due=reader.bookLoans.get("garden-basics").dueMinute;
        s.minute=due-1;assertTrue(Projects.problems(s,reader).isEmpty());
        s.minute=due;Projects.Problem problem=Projects.problems(s,reader).getFirst();
        assertEquals("overdue-books",problem.kind());assertEquals(Map.of("overdueBooks",1.0,"longestOverdueMinutes",0.0),problem.evidence());
        assertTrue(problem.description().contains(BookCatalog.books().stream().filter(b->b.id().equals("garden-basics")).findFirst().orElseThrow().title()));
        assertEquals(List.of("gardening"),problem.subjects());assertTrue(Projects.problems(s,other).isEmpty());
        assertThrows(Engine.Invalid.class,()->Library.borrow(s,reader,"science-everyday",new Engine.Effects()));
        assertThrows(Engine.Invalid.class,()->Library.read(s,reader,"garden-basics",10,"read",new Engine.Effects()));
        s.minute=due+15;assertEquals(15.0,Projects.problems(s,reader).getFirst().evidence().get("longestOverdueMinutes"));
        Library.returnBook(s,reader,"garden-basics",new Engine.Effects());assertTrue(Projects.problems(s,reader).isEmpty());
        Library.borrow(s,reader,"science-everyday",new Engine.Effects());assertTrue(Projects.problems(s,reader).isEmpty());
    }
    @Test void groundedDraftBecomesPublicProjectOnlyAfterOwnedSubjectAndRecipeChecks() throws Exception {
        State s=State.seed();Resident a=resident(s,"a",true),other=resident(s,"b",true);
        Projects.Project p=start(s,a,"nourishment","p1");
        assertEquals("researching",p.status);assertEquals(Set.of(a.id),p.members);assertTrue(a.interests.contains("business"));
        assertTrue(s.products.isEmpty());assertFalse(p.problem.isBlank());assertTrue(p.description.contains(p.concepts.getFirst()));
        assertThrows(Engine.Invalid.class,()->Projects.start(s,other,"work-p1","nourishment","office","stolen",new Engine.Effects()));
        assertThrows(Engine.Invalid.class,()->Projects.start(s,a,"work-p1","nourishment","office","duplicate",new Engine.Effects()));
        assertEquals(1,s.projects.size());assertEquals(1,Projects.publicView(s).projects().size());
        assertEquals(p.description,Projects.publicView(s).projects().getFirst().description());
    }
    @Test void invitationsRequireDeclaredInterestAndRealConversationAndAcceptanceNeverGrantsKnowledge() throws Exception {
        State s=State.seed();Resident a=resident(s,"a",true),b=resident(s,"b",false);study(b,"business",false);
        Projects.Project p=start(s,a,"study-aid","p1");
        assertThrows(Engine.Invalid.class,()->Projects.invite(s,a,b.id,p.id,"Let us develop a useful local study tool together.","i1",new Engine.Effects()));
        meet(s,a,b);assertThrows(Engine.Invalid.class,()->Projects.invite(s,a,b.id,p.id,"Let us develop a useful local study tool together.","i1",new Engine.Effects()));
        act(s,b,ActionType.DECLARE_INTEREST,"business","add",null,null,"interest");
        act(s,a,ActionType.INVITE_PROJECT,b.id,p.id,"Let us develop a useful local study tool together.",null,"i1");
        assertEquals(Set.of(a.id),p.members);assertTrue(s.projectInvitations.get("i1").message.contains("separate explicit endorsement"));
        int points=b.knowledge.get("business").points;
        assertThrows(Engine.Invalid.class,()->Projects.respond(s,a,"i1",true,new Engine.Effects()));
        p.businessEndorsements.add(a.id);
        act(s,b,ActionType.ACCEPT_PROJECT,"i1",null,null,null,"accept");
        assertEquals(new LinkedHashSet<>(List.of(a.id,b.id)),p.members);assertTrue(p.businessEndorsements.isEmpty());assertEquals(points,b.knowledge.get("business").points);
        assertThrows(Engine.Invalid.class,()->Projects.build(s,b,p.id,"build-b",new Engine.Effects()));
        assertTrue(Projects.view(s,a).interestedResidents().stream().anyMatch(r->r.id().equals(b.id)));
    }
    @Test void decliningAndUnqualifiedAcceptanceAreIndependentDecisionsWithNoMembershipSideEffects() throws Exception {
        State s=State.seed();Resident a=resident(s,"a",true),b=resident(s,"b",false),c=resident(s,"c",false);
        Projects.Project p=start(s,a,"study-aid","p1");meet(s,a,b);meet(s,a,c);
        Projects.declareInterest(s,b,"business",null,new Engine.Effects());Projects.declareInterest(s,c,"business",null,new Engine.Effects());
        Projects.invite(s,a,b.id,p.id,"We could research a practical learning aid together.","ib",new Engine.Effects());
        Projects.invite(s,a,c.id,p.id,"We could research a practical learning aid together.","ic",new Engine.Effects());
        assertThrows(Engine.Invalid.class,()->Projects.respond(s,b,"ib",true,new Engine.Effects()));
        assertEquals("proposed",s.projectInvitations.get("ib").status);assertEquals(1,p.members.size());
        Projects.respond(s,c,"ic",false,new Engine.Effects());assertEquals("declined",s.projectInvitations.get("ic").status);
        assertEquals(Set.of(a.id),p.members);assertTrue(b.knowledge.isEmpty());assertTrue(c.knowledge.isEmpty());
    }
    @Test void researchIsTimedGroundedAndFiniteAcrossRepeatedWorkAndProjects() throws Exception {
        State s=State.seed();Resident a=resident(s,"a",true),b=resident(s,"b",false);Projects.Project p=start(s,a,"nourishment","p1");
        b.hunger=80;b.energy=20;b.wetness=60;
        int base=a.knowledge.get("business").points;
        act(s,a,ActionType.RESEARCH_PROJECT,p.id,"observation",null,30,"observe");ticks(s,29);
        assertTrue(a.researchFindings.isEmpty());assertTrue(p.research.isEmpty());assertFalse(Social.available(a));
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.MOVE,"park",null,null,null,"move"));
        Engine.tick(s);assertNull(a.pending);
        Projects.ResearchFinding f=p.research.get("a:observation");assertEquals(1,f.evidence.get("hungryResidents"));assertEquals(1,f.evidence.get("lowEnergyResidents"));
        assertEquals(2,a.knowledge.get("business").researchPoints);assertEquals(base+2,a.knowledge.get("business").points);
        assertTrue(f.result.contains("not purchase commitments"));
        research(s,a,p,"observation","again");assertEquals(1,a.researchFindings.size());assertEquals(base+2,a.knowledge.get("business").points);
        Projects.Project second=start(s,a,"nourishment","p2");research(s,a,second,"observation","observe2");
        assertEquals(1,a.researchFindings.size());assertEquals(base+2,a.knowledge.get("business").points);
        research(s,a,p,"economics","economics");f=p.research.get("a:economics");
        assertEquals(600,f.evidence.get("unitMaterialCostCents"));assertEquals(100,f.evidence.get("exampleMarginAfterPaidLaborCents"));
        assertTrue(f.result.contains("No customers, sales or profit"));assertTrue(s.businesses.isEmpty());
    }
    @Test void buildConsumesMoneyAndTimeThenCreatesRealInventoryAndTestingConsumesPrototype() throws Exception {
        State s=State.seed();Resident a=resident(s,"a",true);Projects.Project p=start(s,a,"nourishment","p1");long balance=a.money;
        assertThrows(Engine.Invalid.class,()->Projects.build(s,a,p.id,"premature",new Engine.Effects()));
        research(s,a,p,"observation","observe");Engine.Effects started=act(s,a,ActionType.BUILD_PROJECT,p.id,null,null,null,"build");
        assertEquals(balance-1200,a.money);assertEquals(1200,s.accounts.get("escrow:build"));assertEquals("prototype_material_reservation",started.payments.getFirst().reason());
        ticks(s,59);assertTrue(s.products.isEmpty());assertEquals(0,a.inventory.getOrDefault("product:p1",0));
        Engine.Effects finished=Engine.tick(s);assertNull(a.pending);assertEquals("completed",finished.completions.getFirst().status());
        assertEquals(1,a.inventory.get("product:p1"));assertEquals("prototyped",p.status);assertFalse(s.accounts.containsKey("escrow:build"));assertEquals(1200,s.accounts.get("materials"));
        Projects.Product product=s.products.get("product:p1");assertEquals(600,product.unitCost);assertEquals(p.id,product.project);
        a.hunger=80;act(s,a,ActionType.RESEARCH_PROJECT,p.id,"test",null,30,"test");ticks(s,29);assertEquals(1,a.inventory.get(product.id));
        Engine.tick(s);Projects.ResearchFinding finding=p.research.get("a:test");
        assertEquals(40,finding.evidence.get("beforeHunger")-finding.evidence.get("afterHunger"),0.00001);
        assertEquals(1,finding.evidence.get("unitsConsumed"));assertFalse(a.inventory.containsKey(product.id));assertEquals("tested",p.status);assertTrue(Projects.tested(p));
        assertThrows(Engine.Invalid.class,()->Projects.use(s,a,product.id,new Engine.Effects()));
        assertThrows(Engine.Invalid.class,()->Projects.research(s,a,p.id,"test",30,"empty-test",new Engine.Effects()));
        act(s,a,ActionType.BUILD_PROJECT,p.id,null,null,null,"build2");ticks(s,60);assertSame(product,s.products.get(product.id));
        assertEquals(1,s.products.size());assertEquals(2,p.prototypesBuilt);assertEquals(balance-2400,a.money);
    }
    @Test void insufficientMoneyWrongWorkplaceClosedHoursInvalidMethodAndShortWorkAreAtomic() throws Exception {
        State s=State.seed();Resident a=resident(s,"a",true);Projects.Project p=start(s,a,"recovery-kit","p1");research(s,a,p,"observation","observe");
        a.money=1199;Engine.Effects effects=new Engine.Effects();
        assertThrows(Engine.Invalid.class,()->Projects.build(s,a,p.id,"poor",effects));assertEquals(1199,a.money);assertNull(a.pending);assertTrue(effects.payments.isEmpty());
        a.money=10000;place(s,a,"park");assertThrows(Engine.Invalid.class,()->Projects.build(s,a,p.id,"away",effects));
        place(s,a,"office");Building office=s.buildings.get("office");office.openMinute=480;office.closeMinute=(int)s.minute+59;
        assertThrows(Engine.Invalid.class,()->Projects.build(s,a,p.id,"closed",effects));assertEquals(10000,a.money);assertFalse(s.accounts.containsKey("escrow:closed"));
        office.closeMinute=0;office.openMinute=0;
        assertThrows(Engine.Invalid.class,()->Projects.research(s,a,p.id,null,30,"null-method",effects));
        assertThrows(Engine.Invalid.class,()->Projects.research(s,a,p.id,"magic",30,"bad-method",effects));
        assertThrows(Engine.Invalid.class,()->Projects.research(s,a,p.id,"observation",29,"short",effects));
        assertNull(a.pending);assertTrue(effects.payments.isEmpty());assertEquals(1,p.research.size());
    }
    @Test void interruptionRefundsReservedMaterialsAndNeverCreditsPartialFindingOrProduct() throws Exception {
        State s=State.seed();Resident a=resident(s,"a",true);Projects.Project p=start(s,a,"restorative-art","p1");
        act(s,a,ActionType.RESEARCH_PROJECT,p.id,"observation",null,30,"research");ticks(s,12);a.energy=0;
        Engine.Effects interrupted=Engine.tick(s);assertNull(a.pending);assertTrue(a.researchFindings.isEmpty());assertTrue(p.research.isEmpty());assertEquals("cancelled",interrupted.completions.getFirst().status());
        a.energy=100;research(s,a,p,"observation","complete");long balance=a.money;
        act(s,a,ActionType.BUILD_PROJECT,p.id,null,null,null,"build");ticks(s,20);a.paused=true;
        interrupted=Engine.tick(s);assertEquals(balance,a.money);assertNull(a.pending);assertTrue(s.products.isEmpty());assertEquals(0,p.prototypesBuilt);
        assertFalse(s.accounts.containsKey("escrow:build"));assertEquals("prototype_material_refund",interrupted.payments.getFirst().reason());assertEquals("cancelled",interrupted.completions.getFirst().status());
    }
    @Test void allSupportedEffectsAreBoundedUseConsumesOnlyOwnedUnitsAndBoostIsNotStacked() {
        State s=State.seed();Resident a=resident(s,"a",false),b=resident(s,"b",false);
        for(Projects.Recipe r:Projects.recipes()) {
            Projects.Product p=new Projects.Product();p.id="product:"+r.id();p.title=r.title();p.recipe=r.id();s.products.put(p.id,p);a.inventory.put(p.id,2);
            a.hunger=30;a.energy=95;a.wetness=25;
            assertThrows(Engine.Invalid.class,()->Projects.use(s,b,p.id,new Engine.Effects()));
            Projects.use(s,a,p.id,new Engine.Effects());assertEquals(1,a.inventory.get(p.id));
            switch(r.id()) {
                case "nourishment" -> {assertEquals(0,a.hunger);assertEquals(s.minute,a.lastMealMinute);}
                case "weather-care" -> {assertEquals(0,a.wetness);assertEquals(s.minute+120,a.weatherProtectionUntil);}
                case "study-aid" -> assertEquals(s.minute+120,a.studyBoostUntil);
                case "restorative-art" -> assertEquals(100,a.energy);
                case "recovery-kit" -> {assertEquals(100,a.energy);assertEquals(0,a.wetness);}
            }
            Projects.use(s,a,p.id,new Engine.Effects());assertFalse(a.inventory.containsKey(p.id));
            assertTrue(a.energy<=100&&a.hunger>=0&&a.wetness>=0);assertTrue(a.studyBoostUntil<=s.minute+120);assertTrue(a.weatherProtectionUntil<=s.minute+120);
        }
    }
    @Test void projectResearchAndInvitationsRemainPrivateWhileDeliberatelyPublishedDesignsAreVisible() throws Exception {
        State s=State.seed();Resident a=resident(s,"a",true),b=resident(s,"b",true),outsider=resident(s,"c",false);
        Projects.Project p=start(s,a,"weather-care","p1");research(s,a,p,"observation","observe");meet(s,a,b);Projects.declareInterest(s,b,"business",null,new Engine.Effects());
        Projects.invite(s,a,b.id,p.id,"Private proposal: discuss this weather-care prototype.","invitation",new Engine.Effects());
        assertEquals(1,Projects.view(s,b).invitations().size());assertTrue(Projects.view(s,outsider).invitations().isEmpty());
        assertEquals(1,Projects.view(s,a).projects().getFirst().research().size());assertTrue(Projects.view(s,outsider).projects().getFirst().research().isEmpty());
        String publiclyVisible=JSON.writeValueAsString(Projects.publicView(s));
        assertFalse(publiclyVisible.contains("Private proposal"));assertFalse(publiclyVisible.contains("beforeHunger"));assertFalse(publiclyVisible.contains("researchFindings"));assertFalse(publiclyVisible.contains("money"));
        assertTrue(publiclyVisible.contains(p.title));assertTrue(publiclyVisible.contains(p.problem));
        p.businessId="business:existing";assertThrows(Engine.Invalid.class,()->Projects.respond(s,b,"invitation",true,new Engine.Effects()));
        assertEquals(Set.of(a.id),p.members);assertEquals("proposed",s.projectInvitations.get("invitation").status);
    }
    @Test void restartRetainsReservedBuildAndFindingsWithoutDoubleCompletionAndLegacyDefaultsAreSafe() throws Exception {
        State s=State.seed();Resident a=resident(s,"a",true);Projects.Project p=start(s,a,"nourishment","p1");research(s,a,p,"observation","observe");
        act(s,a,ActionType.BUILD_PROJECT,p.id,null,null,null,"build");ticks(s,25);
        State restored=JSON.readValue(JSON.writeValueAsString(s),State.class);a=restored.agents.get("a");ticks(restored,35);
        p=restored.projects.get("p1");assertEquals(1,a.inventory.get(p.productId));assertEquals(1,p.prototypesBuilt);assertEquals(1,a.researchFindings.size());assertEquals(2,a.knowledge.get("business").researchPoints);
        ticks(restored,2);assertEquals(1,a.inventory.get(p.productId));assertEquals(1200,restored.accounts.get("materials"));
        Resident old=JSON.readValue("{\"id\":\"old\"}",Resident.class);assertTrue(old.interests.isEmpty());assertTrue(old.researchFindings.isEmpty());assertEquals(0,old.studyBoostUntil);
        State empty=JSON.readValue("{}",State.class);assertTrue(empty.projects.isEmpty());assertTrue(empty.products.isEmpty());assertTrue(empty.projectInvitations.isEmpty());
    }
    @Test void researchContributionCapsAtTwelvePointsWithoutReplacingBookDepthRequirements() throws Exception {
        State s=State.seed();Resident a=resident(s,"a",true);int bookPoints=a.knowledge.get("business").points;
        for(String recipe:List.of("nourishment","weather-care","study-aid","restorative-art","recovery-kit")) {
            Projects.Project p=start(s,a,recipe,"p-"+recipe);research(s,a,p,"observation","observe-"+recipe);research(s,a,p,"economics","economics-"+recipe);
        }
        assertEquals(10,a.researchFindings.size());assertEquals(12,a.knowledge.get("business").researchPoints);assertEquals(bookPoints+12,a.knowledge.get("business").points);
        Library.refreshKnowledge(a);assertEquals(bookPoints+12,a.knowledge.get("business").points);
    }
    @Test void duplicateTicksCannotCreditWorkAndMissingMaterialReservationCannotMintAPrototype() throws Exception {
        State s=State.seed();Resident a=resident(s,"a",true);Projects.Project p=start(s,a,"nourishment","p1");
        act(s,a,ActionType.RESEARCH_PROJECT,p.id,"observation",null,30,"observe");Engine.tick(s);
        int actualWork=a.pending.workMinutes;Projects.tick(s,a,new Engine.Effects());Projects.tick(s,a,new Engine.Effects());
        assertEquals(actualWork,a.pending.workMinutes);assertTrue(a.researchFindings.isEmpty());ticks(s,29);
        act(s,a,ActionType.BUILD_PROJECT,p.id,null,null,null,"build");
        s.accounts.remove("escrow:build");Engine.Effects cancelled=Engine.tick(s);
        assertNull(a.pending);assertEquals("cancelled",cancelled.completions.getFirst().status());assertTrue(s.products.isEmpty());assertEquals(0,p.prototypesBuilt);
    }
    @Test void nourishingProductAtHomeSatisfiesFoodRequiredAfterHungryWake() {
        State s=State.seed();Resident a=resident(s,"a",false);place(s,a,a.home);a.hunger=95;a.hungerWakeNeedsFood=true;
        Projects.Product product=new Projects.Product();product.id="product:meal";product.title="Meal prototype";product.recipe="nourishment";s.products.put(product.id,product);a.inventory.put(product.id,1);
        act(s,a,ActionType.USE_PRODUCT,product.id,null,null,null,"use");
        assertEquals(55,a.hunger);assertFalse(a.hungerWakeNeedsFood);assertEquals(s.minute,a.lastMealMinute);assertEquals(55,a.hungerAfterLastMeal);
    }
}
