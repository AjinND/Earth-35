package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.AgentService;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class VenueClosingTest {
    private Resident visitor(State s,String id,String location) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=location;
        Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(id,a);return a;
    }
    private void act(State s,Resident a,ActionType action,String target,String id) {
        Engine.apply(s,a,new Command(id,action,target,null,null,null,null),id,new Engine.Effects());
    }
    private int visitors(State s,String location) {
        return AgentService.gatheringPlaces(s).stream().filter(p->p.id().equals(location)).findFirst().orElseThrow().residentsHere();
    }
    private Seating.View seats(State s,String location) {
        return Seating.view(s.buildings.get(location),s.agents.values().stream().map(WorldStore::publicAgent)
            .filter(a->location.equals(a.location())).map(PublicAgent::seatIndex).toList());
    }
    private void assertOutside(State s,Resident a,Building b) {
        var door=Places.entrance(b);var publicAgent=WorldStore.publicAgent(a);
        assertEquals("road",a.location);assertEquals("road",publicAgent.location());
        assertEquals(door.x(),publicAgent.x(),.000001);assertEquals(door.z(),publicAgent.z(),.000001);
        assertEquals("Idle",publicAgent.activity());assertNull(publicAgent.seatIndex());assertNull(a.pending);
    }
    private long closingFacts(Engine.Effects e,String actor) {
        return e.facts.stream().filter(f->actor.equals(f.actor())&&f.message().toLowerCase().contains("closed")).count();
    }

    @Test void closingReleasesIdleAndSeatedCustomersAndUpdatesPublicOccupancy() {
        State s=State.prototypeSeed();s.minute=1199;Building coffee=s.buildings.get("coffee");
        Resident idle=visitor(s,"idle","coffee"),seated=visitor(s,"seated","coffee");
        act(s,seated,ActionType.USE_OBJECT,coffee.objects.keySet().iterator().next(),"rest");
        assertEquals(2,visitors(s,"coffee"));assertEquals(1,seats(s,"coffee").occupied());
        assertNotNull(WorldStore.publicAgent(seated).seatIndex());

        Engine.Effects closed=Engine.tick(s);

        assertFalse(Places.open(coffee,s.minute));assertEquals(1800,Places.nextOpen(coffee,s.minute));
        assertOutside(s,idle,coffee);assertOutside(s,seated,coffee);assertEquals(0,visitors(s,"coffee"));
        assertEquals(0,seats(s,"coffee").occupied());assertEquals(Seating.capacity(coffee),seats(s,"coffee").availableSeats());
        assertEquals(1,closed.completions.size());assertEquals("rest",closed.completions.getFirst().id());
        assertEquals("cancelled",closed.completions.getFirst().status());assertTrue(closed.completions.getFirst().result().toLowerCase().contains("closed"));
        assertEquals(1,closingFacts(closed,idle.id));assertEquals(1,closingFacts(closed,seated.id));
        Engine.Effects next=Engine.tick(s);assertTrue(next.completions.isEmpty());
        assertEquals(0,closingFacts(next,idle.id));assertEquals(0,closingFacts(next,seated.id));
    }

    @Test void closedVisitorsAreRepairedOnTheNextTickAfterReloadEvenWhenPaused() throws Exception {
        State s=State.prototypeSeed();s.minute=1500;Building coffee=s.buildings.get("coffee");
        Resident idle=visitor(s,"saved-idle","coffee"),paused=visitor(s,"saved-paused","coffee");
        paused.paused=true;paused.pending=Engine.pending("saved-rest","REST",coffee.id,1600);paused.pending.seatIndex=0;
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);

        Engine.Effects repaired=Engine.tick(restored);

        assertOutside(restored,restored.agents.get(idle.id),restored.buildings.get(coffee.id));
        Resident savedPaused=restored.agents.get(paused.id);assertOutside(restored,savedPaused,restored.buildings.get(coffee.id));
        assertTrue(savedPaused.paused);assertEquals("paused",WorldStore.publicAgent(savedPaused).status());
        assertEquals(0,visitors(restored,coffee.id));assertEquals(1,repaired.completions.size());
        assertEquals("saved-rest",repaired.completions.getFirst().id());assertEquals("cancelled",repaired.completions.getFirst().status());
        Engine.Effects next=Engine.tick(restored);assertTrue(next.completions.isEmpty());
        assertEquals(0,closingFacts(next,idle.id));assertEquals(0,closingFacts(next,paused.id));
    }

    @Test void aTimedActivityFinishingAtClosingCompletesOnceBeforeTheResidentLeaves() {
        State s=State.prototypeSeed();s.minute=1170;Building coffee=s.buildings.get("coffee");
        Resident a=visitor(s,"resting","coffee");act(s,a,ActionType.REST,null,"on-time-rest");
        while(s.minute<1199)Engine.tick(s);
        assertEquals(coffee.id,a.location);assertNotNull(a.pending);assertEquals(1200,a.pending.until);

        Engine.Effects closed=Engine.tick(s);

        assertOutside(s,a,coffee);assertEquals(1,closed.completions.size());
        assertEquals("on-time-rest",closed.completions.getFirst().id());assertEquals("completed",closed.completions.getFirst().status());
        assertEquals("REST completed",closed.completions.getFirst().result());
        assertTrue(Engine.tick(s).completions.isEmpty());
    }

    @Test void venueClosingEndsChatsWithoutAddingDialogueAndCannotReopenThemOutside() {
        State s=State.prototypeSeed();s.minute=1199;Building coffee=s.buildings.get("coffee");
        Resident first=visitor(s,"first","coffee"),second=visitor(s,"second","coffee");
        Conversations.speak(s,first,second,"I enjoyed the coffee.");Conversations.speak(s,second,first,"So did I.");
        Conversation chat=s.conversations.get(Conversations.key(first.id,second.id));
        List<Turn> turns=List.copyOf(chat.turns);long turnSequence=chat.turnSequence;
        assertTrue(Conversations.active(s,first));assertTrue(Conversations.active(s,second));

        Engine.Effects closed=Engine.tick(s);

        assertOutside(s,first,coffee);assertOutside(s,second,coffee);assertTrue(chat.closed);
        assertFalse(Conversations.active(s,first));assertFalse(Conversations.active(s,second));
        assertEquals(turns,chat.turns);assertEquals(turnSequence,chat.turnSequence);
        assertTrue(closed.facts.stream().noneMatch(f->"conversation".equals(f.kind())));
        Engine.tick(s);assertTrue(chat.closed);assertEquals(turns,chat.turns);
    }

    @Test void homesAllDayBuildingsAndOpenVenuesKeepTheirResidentsInside() {
        State s=State.prototypeSeed();s.minute=1199;
        Resident home=visitor(s,"home","apt-101"),allDay=visitor(s,"all-day","office"),open=visitor(s,"still-open","restaurant");
        Building park=s.buildings.get("park");park.openMinute=360;park.closeMinute=1200;
        Resident outdoors=visitor(s,"outdoors","park");

        Engine.Effects e=Engine.tick(s);

        for(Resident a:List.of(home,allDay,open,outdoors)) {
            Building b=s.buildings.get(a.location);assertNotNull(b);
            assertEquals(b.x,a.x,.000001);assertEquals(b.z,a.z,.000001);assertNull(a.pending);
            assertEquals(0,closingFacts(e,a.id));
        }
        assertEquals("apt-101",home.location);assertEquals("office",allDay.location);
        assertEquals("restaurant",open.location);assertEquals("park",outdoors.location);
        assertFalse(Places.open(park,s.minute));
    }

    @Test void overnightOpeningKeepsVisitorsThroughMidnightUntilItsActualClosingTime() {
        State s=State.prototypeSeed();Building coffee=s.buildings.get("coffee");
        coffee.openMinute=1320;coffee.closeMinute=120;coffee.openDays=List.of(0);
        s.minute=1439;Resident a=visitor(s,"overnight","coffee");

        Engine.tick(s);assertEquals(1440,s.minute);assertTrue(Places.open(coffee,s.minute));assertEquals(coffee.id,a.location);
        s.minute=1558;Engine.tick(s);assertTrue(Places.open(coffee,s.minute));assertEquals(coffee.id,a.location);
        Engine.Effects closed=Engine.tick(s);

        assertEquals(1560,s.minute);assertFalse(Places.open(coffee,s.minute));assertOutside(s,a,coffee);
        assertEquals(1,closingFacts(closed,a.id));assertTrue(closed.completions.isEmpty());
    }

    @Test void approvedStaffStayInsideAfterPublicClosingUntilTheirPermitExpires() {
        State s=State.prototypeSeed();s.minute=1079;Building office=s.buildings.get("office");
        office.openMinute=540;office.closeMinute=1080;
        Resident employee=visitor(s,"employee","office"),customer=visitor(s,"customer","office");
        employee.job="job-office";act(s,employee,ActionType.REQUEST_OVERTIME,null,"access");employee.workPermit.until=1082;
        assertTrue(Places.canEnter(s,employee,office,s.minute));

        Engine.Effects closed=Engine.tick(s);

        assertFalse(Places.open(office,s.minute));assertTrue(Places.canEnter(s,employee,office,s.minute));
        assertEquals(office.id,employee.location);assertNotNull(employee.workPermit);assertEquals(0,closingFacts(closed,employee.id));
        assertOutside(s,customer,office);
        Engine.tick(s);assertEquals(office.id,employee.location);
        Engine.Effects expired=Engine.tick(s);assertNull(employee.workPermit);assertOutside(s,employee,office);
        assertEquals(1,closingFacts(expired,employee.id));assertTrue(expired.completions.isEmpty());
    }

    @Test void acceptedStaffWorkFinishesAndPaysOnceBeforeAnExpiredPermitRequiresDeparture() {
        State s=State.prototypeSeed();s.minute=1080;Building office=s.buildings.get("office");
        office.openMinute=540;office.closeMinute=1020;
        Resident a=visitor(s,"working","office");a.job="job-office";
        act(s,a,ActionType.REQUEST_OVERTIME,null,"staff-access");
        long balance=a.money;var accepted=new Engine.Effects();
        Engine.apply(s,a,new Command("funded-work",ActionType.WORK,null,null,"overtime",null,null),"funded-work",accepted);
        assertEquals(3000,s.accounts.get("escrow:funded-work"));assertEquals(1140,a.pending.until);
        a.workPermit.until=1082;
        while(s.minute<1139) {
            Engine.Effects working=Engine.tick(s);
            assertEquals(office.id,a.location);assertNotNull(a.pending);assertEquals("WORK",a.pending.type);
            assertTrue(working.completions.isEmpty());assertEquals(balance,a.money);
        }
        assertNull(a.workPermit);assertFalse(Places.canEnter(s,a,office,s.minute));

        Engine.Effects finished=Engine.tick(s);

        assertOutside(s,a,office);assertEquals(balance+3000,a.money);assertFalse(s.accounts.containsKey("escrow:funded-work"));
        assertEquals(1,finished.completions.size());assertEquals("funded-work",finished.completions.getFirst().id());
        assertEquals("completed",finished.completions.getFirst().status());assertEquals(1,closingFacts(finished,a.id));
        assertEquals(1,finished.payments.stream().filter(p->a.id.equals(p.destination())&&"overtime_wage".equals(p.reason())).count());
        Engine.Effects next=Engine.tick(s);assertTrue(next.completions.isEmpty());assertEquals(balance+3000,a.money);
        assertTrue(next.payments.stream().noneMatch(p->a.id.equals(p.destination())));
    }

    @Test void closingRefundsUnfinishedSavedPrototypeAndProductionReservationsExactlyOnce() {
        State s=State.prototypeSeed();s.minute=1199;Building coffee=s.buildings.get("coffee");
        Resident builder=visitor(s,"builder","coffee"),manufacturer=visitor(s,"manufacturer","coffee");
        // Saved work may outlast current venue hours; its material escrow remains real money.
        Projects.Project project=new Projects.Project();project.id="prototype";project.founder=builder.id;
        project.title="Lunch prototype";project.subject="business";project.recipe="nourishment";project.location=coffee.id;
        project.members.add(builder.id);s.projects.put(project.id,project);
        builder.pending=Engine.pending("build","BUILD_PROJECT",project.id,1230);builder.pending.startedAt=1170;
        builder.pending.workMinutes=29;builder.pending.reservedCost=1200;
        long builderBalance=builder.money;Engine.pay(s,builder.id,"escrow:build",1200,"prototype_material_reservation",new Engine.Effects());

        Projects.Product product=new Projects.Product();product.id="product:lunch";product.recipe="nourishment";product.unitCost=600;
        s.products.put(product.id,product);
        Ventures.Business business=new Ventures.Business();business.id="business:lunch";business.name="Field Lunch";
        business.location=coffee.id;business.productId=product.id;business.owners.add(manufacturer.id);s.businesses.put(business.id,business);
        s.accounts.put(business.id,20000L);
        manufacturer.pending=Engine.pending("manufacture","PRODUCE_PRODUCT",business.id,1230);manufacturer.pending.startedAt=1170;
        manufacturer.pending.workMinutes=29;manufacturer.pending.projectMethod="2";manufacturer.pending.reservedCost=1200;
        Engine.pay(s,business.id,"escrow:manufacture:manufacture",1200,"production_material_reservation",new Engine.Effects());

        Engine.Effects closed=Engine.tick(s);

        assertOutside(s,builder,coffee);assertOutside(s,manufacturer,coffee);assertEquals(builderBalance,builder.money);
        assertEquals(20000L,s.accounts.get(business.id));assertFalse(s.accounts.containsKey("escrow:build"));
        assertFalse(s.accounts.containsKey("escrow:manufacture:manufacture"));assertEquals(0,project.prototypesBuilt);
        assertEquals("researching",project.status);assertFalse(s.products.containsKey("product:"+project.id));
        assertFalse(builder.inventory.containsKey("product:"+project.id));assertEquals(0,business.stock);assertEquals(0,business.materialsExpenseCents);
        assertEquals(2,closed.completions.size());assertTrue(closed.completions.stream().allMatch(c->"cancelled".equals(c.status())&&c.result().contains("closed")));
        assertEquals(1,closed.payments.stream().filter(p->"prototype_material_refund".equals(p.reason())&&p.amount()==1200).count());
        assertEquals(1,closed.payments.stream().filter(p->"production_material_refund".equals(p.reason())&&p.amount()==1200).count());
        Engine.Effects next=Engine.tick(s);assertTrue(next.completions.isEmpty());assertTrue(next.payments.isEmpty());
        assertEquals(builderBalance,builder.money);assertEquals(20000L,s.accounts.get(business.id));
    }

    @Test void closingDuringAnIndoorEntryRejectsTravelFromEitherSideOfTheThreshold() {
        for(String pace:List.of("MOVE","RUN"))for(boolean alreadyInside:List.of(false,true)) {
            State s=State.prototypeSeed();s.minute=1199;Building coffee=s.buildings.get("coffee");var door=Places.entrance(coffee);
            Resident a=visitor(s,"entering","coffee");a.location=alreadyInside?coffee.id:"road";
            a.x=door.x();a.z=alreadyInside?coffee.z+.25:door.z();
            a.pending=Engine.pending("arrival",pace,coffee.id,0);a.pending.entering=true;a.pending.frontDoorRoute=true;
            a.pending.route.add(new double[]{coffee.x,coffee.z});

            Engine.Effects closed=Engine.tick(s);

            assertOutside(s,a,coffee);assertEquals(0,visitors(s,coffee.id));assertEquals(1,closed.completions.size());
            assertEquals("arrival",closed.completions.getFirst().id());assertEquals("rejected",closed.completions.getFirst().status());
            assertTrue(closed.completions.getFirst().result().toLowerCase().contains("closed"));
            assertTrue(Engine.tick(s).completions.isEmpty());
        }
    }
}
