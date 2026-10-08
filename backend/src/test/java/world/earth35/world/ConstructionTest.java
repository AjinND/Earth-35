package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;

class ConstructionTest {
    private State setup() {
        State s=State.seed();s.minute=600;
        for(String id:List.of("builder","second")) {
            State.Resident a=new State.Resident();a.id=id;a.name=id;a.home="apt-101";
            a.location="road";s.agents.put(id,a);
        }
        atSite(s,"builder","construction-201");atSite(s,"second","construction-201");return s;
    }
    private void atSite(State s,String resident,String site) {
        State.Resident a=s.agents.get(resident);var door=Places.entrance(s.buildings.get(site));a.x=door.x();a.z=door.z();a.location="road";
    }
    private Engine.Effects act(State s,String resident,ActionType action,String target,String item,Long amount,String message,String id) {
        Engine.Effects e=new Engine.Effects();Engine.apply(s,s.agents.get(resident),new Command(id,action,target,message,item,null,amount),id,e);return e;
    }
    private long total(State s) {return s.accounts.values().stream().mapToLong(Long::longValue).sum()+s.agents.values().stream().mapToLong(a->a.money).sum();}
    private void minutes(State s,int n) {for(int i=0;i<n;i++)Engine.tick(s);}
    private String clearPlot(State s) {
        for(int x=-550;x<220;x+=17)for(int z=-500;z<330;z+=17) {
            String target="point:"+x+","+z;
            try {
                ObjectMapper json=new ObjectMapper();State copy=json.convertValue(s,State.class);
                Construction.start(copy,copy.agents.get("builder"),target,"bakery","Sample Bakery",null,"probe",new Engine.Effects());return target;
            }catch(Engine.Invalid unavailable) {}
        }
        throw new AssertionError("No clear construction plot");
    }
    @Test void existingSitesAreFundedOnceAndNeverAdvanceWithoutChosenWork() {
        State s=setup();long total=total(s),fund=s.accounts.get(Construction.account("construction-201"));
        Engine.Effects e=new Engine.Effects();Construction.initializeCitySites(s,e);
        assertTrue(e.payments.isEmpty());assertEquals(fund,s.accounts.get(Construction.account("construction-201")));
        minutes(s,60);assertEquals(0,s.construction.get("construction-201").completedHours);
        assertNull(s.agents.get("builder").pending);assertEquals("",s.agents.get("builder").job);assertEquals(total,total(s));
        assertEquals(10,Livelihood.view(s,s.agents.get("builder")).construction().sites().size());
    }
    @Test void unemployedBuilderEarnsOnlyAfterFullOnsiteHourAndFundsAreConserved() {
        State s=setup();State.Resident a=s.agents.get("builder");long before=a.money,total=total(s);
        act(s,a.id,ActionType.BUILD_CONSTRUCTION,"construction-201",null,null,null,"shift");
        assertEquals(3000L,s.accounts.get("escrow:construction:shift"));assertTrue(Engine.busy(a));
        minutes(s,59);assertEquals(before,a.money);assertEquals(0,s.construction.get("construction-201").completedHours);
        minutes(s,1);assertEquals(before+2000,a.money);assertNull(a.pending);assertEquals("",a.job);
        assertEquals(1,s.construction.get("construction-201").completedHours);assertEquals(0,s.construction.get("construction-201").reservedHours);
        assertFalse(s.accounts.containsKey("escrow:construction:shift"));assertEquals(total,total(s));
    }
    @Test void concurrentLastHourCannotBeOverbookedAndCompletionOpensActualBuilding() {
        State s=setup();Construction.Site site=s.construction.get("construction-201");site.completedHours=site.requiredHours-1;
        act(s,"builder",ActionType.BUILD_CONSTRUCTION,site.id,null,null,null,"last");
        long funds=s.accounts.get(Construction.account(site.id));
        assertThrows(Engine.Invalid.class,()->act(s,"second",ActionType.BUILD_CONSTRUCTION,site.id,null,null,null,"racing"));
        assertEquals(funds,s.accounts.get(Construction.account(site.id)));minutes(s,60);
        assertEquals("completed",site.status);assertEquals("house",s.buildings.get(site.id).type);assertEquals(1,s.buildings.get(site.id).capacity);
        assertTrue(Places.open(s.buildings.get(site.id),s.minute));
        assertThrows(Engine.Invalid.class,()->act(s,"builder",ActionType.BUILD_CONSTRUCTION,site.id,null,null,null,"extra"));
    }
    @Test void leavingPausingOrMissingEscrowCancelsWithoutUnfundedPayOrProgress() {
        for(String interruption:List.of("leave","pause","escrow","hunger","fatigue")) {
            State s=setup();State.Resident a=s.agents.get("builder");long money=a.money,funds=s.accounts.get(Construction.account("construction-201"));
            act(s,a.id,ActionType.BUILD_CONSTRUCTION,"construction-201",null,null,null,"hour");minutes(s,10);
            switch(interruption) {
                case "leave"->a.x+=10;
                case "pause"->a.paused=true;
                case "escrow"->s.accounts.remove("escrow:construction:hour");
                case "hunger"->a.hunger=98;
                case "fatigue"->a.energy=0;
            }
            Engine.Effects e=Engine.tick(s);assertNull(a.pending);assertEquals(money,a.money);
            assertEquals(0,s.construction.get("construction-201").completedHours);assertEquals(0,s.construction.get("construction-201").reservedHours);
            assertTrue(e.completions.stream().anyMatch(c->"cancelled".equals(c.status())));
            if(!interruption.equals("escrow"))assertEquals(funds,s.accounts.get(Construction.account("construction-201")));
            assertTrue(e.payments.stream().noneMatch(p->"construction_wage".equals(p.reason())));
        }
    }
    @Test void duplicateAndSkippedMinutesCannotProduceWorkOrWages() {
        State s=setup();State.Resident a=s.agents.get("builder");long money=a.money;
        act(s,a.id,ActionType.BUILD_CONSTRUCTION,"construction-201",null,null,null,"hour");
        Construction.tick(s,a,new Engine.Effects());assertEquals(0,a.pending.workMinutes);
        Engine.tick(s);Construction.tick(s,a,new Engine.Effects());assertEquals(1,a.pending.workMinutes);
        s.minute+=60;Construction.tick(s,a,new Engine.Effects());assertNull(a.pending);assertEquals(money,a.money);
        assertEquals(0,s.construction.get("construction-201").completedHours);
    }
    @Test void saveRestoreFinishesReservationExactlyOnce() throws Exception {
        State s=setup();long money=s.agents.get("builder").money,total=total(s);
        act(s,"builder",ActionType.BUILD_CONSTRUCTION,"construction-201",null,null,null,"saved");minutes(s,17);
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);
        minutes(restored,43);assertEquals(money+2000,restored.agents.get("builder").money);assertEquals(total,total(restored));
        minutes(restored,1);assertEquals(money+2000,restored.agents.get("builder").money);assertEquals(1,restored.construction.get("construction-201").completedHours);
    }
    @Test void workRequiresEntranceAndFundedFullHourButDoesNotRequireEmployment() {
        State s=setup();State.Resident a=s.agents.get("builder");a.x+=10;
        assertThrows(Engine.Invalid.class,()->act(s,a.id,ActionType.BUILD_CONSTRUCTION,"construction-201",null,null,null,"away"));atSite(s,a.id,"construction-201");
        s.minute=1141;assertThrows(Engine.Invalid.class,()->act(s,a.id,ActionType.BUILD_CONSTRUCTION,"construction-201",null,null,null,"late"));
        s.minute=1140;act(s,a.id,ActionType.BUILD_CONSTRUCTION,"construction-201",null,null,null,"closing");minutes(s,60);assertNull(a.pending);
        s.minute=600;s.accounts.put(Construction.account("construction-201"),2999L);
        assertThrows(Engine.Invalid.class,()->act(s,a.id,ActionType.BUILD_CONSTRUCTION,"construction-201",null,null,null,"unfunded"));
    }
    @Test void initiatingNewSiteIsExplicitFundedAndCreatesNoAutomaticWorkOrCareer() {
        State s=setup();String plot=clearPlot(s);long total=total(s),money=s.agents.get("builder").money;
        act(s,"builder",ActionType.START_CONSTRUCTION,plot,"bakery",25000L,"Chosen Bakery","new");
        assertEquals(money-25000,s.agents.get("builder").money);assertEquals(total,total(s));assertNull(s.agents.get("builder").pending);
        assertEquals("",s.agents.get("builder").job);assertEquals(0,s.construction.get("site:new").completedHours);
        assertThrows(Engine.Invalid.class,()->act(s,"second",ActionType.START_CONSTRUCTION,plot,"bakery",24000L,"Overlap","bad"));
        atSite(s,"builder","site:new");Construction.Site site=s.construction.get("site:new");site.requiredHours=1;
        act(s,"builder",ActionType.BUILD_CONSTRUCTION,site.id,null,null,null,"finish");minutes(s,60);
        assertEquals("bakery",s.buildings.get(site.id).type);assertEquals(0L,s.accounts.get(Construction.account(site.id)));
        assertEquals(money-1000,s.agents.get("builder").money);assertEquals(total,total(s));
    }
    @Test void dryCornersCannotAuthorizeBuildingAcrossNarrowRiverWater() {
        State s=setup();double x=-175.5,z=-434.5;
        assertTrue(IslandGeography.dryLand(x,z));assertTrue(IslandGeography.dryLand(x,z+3.6));
        for(double dx:new double[]{-4,4})for(double dz:new double[]{-4,4})assertTrue(IslandGeography.dryLand(x+dx,z+dz));
        assertFalse(IslandGeography.dryLand(-179,z));long money=s.agents.get("builder").money;
        Engine.Invalid rejected=assertThrows(Engine.Invalid.class,()->act(s,"builder",ActionType.START_CONSTRUCTION,"point:"+x+","+z,"bakery",null,"River crossing","wet"));
        assertTrue(rejected.getMessage().contains("river"));assertEquals(money,s.agents.get("builder").money);
        assertFalse(s.buildings.containsKey("site:wet"));
    }
    @Test void selfBuildFundsOnlyMaterialsShowsRealWorkersAndAssignsOwnedHomeOnlyAfterCompletion() {
        State s=setup();Construction.Site site=s.construction.get("construction-201");State.Resident owner=s.agents.get("builder");
        site.sponsor=owner.id;site.laborMode="self";site.hourlyWageCents=0;site.requiredHours=1;
        String oldHome=owner.home;long money=owner.money,total=total(s),fund=s.accounts.get(Construction.account(site.id));
        assertThrows(Engine.Invalid.class,()->act(s,"second",ActionType.BUILD_CONSTRUCTION,site.id,null,null,null,"outsider"));
        act(s,owner.id,ActionType.BUILD_CONSTRUCTION,site.id,null,null,null,"self");
        assertEquals(1000L,s.accounts.get("escrow:construction:self"));minutes(s,30);
        Construction.SiteView visible=Construction.view(s).sites().stream().filter(v->site.id.equals(v.id())).findFirst().orElseThrow();
        assertEquals(30,visible.completedWorkMinutes());assertEquals(60,visible.requiredWorkMinutes());assertFalse(visible.acceptingWorkers());
        assertEquals(List.of(owner.id),visible.workers().stream().map(Construction.WorkerView::id).toList());assertTrue(visible.workers().getFirst().selfBuild());
        assertEquals(oldHome,owner.home);assertEquals("construction",s.buildings.get(site.id).type);assertEquals(0,site.completedHours);
        minutes(s,30);assertEquals(site.id,owner.home);assertEquals("house",s.buildings.get(site.id).type);
        assertEquals(money+fund-1000,owner.money);assertEquals(total,total(s));
        assertTrue(Construction.view(s).sites().stream().filter(v->site.id.equals(v.id())).findFirst().orElseThrow().workers().isEmpty());
    }
    @Test void startingSelfBuildCanExplicitlyFinanceMaterialsAndDoesNotBorrowForAnInvalidSite() {
        State s=setup();String plot=clearPlot(s);State.Resident owner=s.agents.get("builder");owner.money=1000;long total=total(s);
        Engine.Effects e=new Engine.Effects();
        assertThrows(Engine.Invalid.class,()->Construction.start(s,owner,"point:-175.5,-434.5","house","Invalid river plot",null,3,"self","wet-loan",e));
        assertTrue(s.loans.isEmpty());assertTrue(e.payments.isEmpty());
        Construction.start(s,owner,plot,"house","Own House",null,5,"self","own-house",e);
        assertEquals(15000,s.loans.get("loan:own-house").principalCents);assertEquals(0,owner.money);assertEquals(total,total(s));
        Construction.Site site=s.construction.get("site:own-house");assertEquals("self",site.laborMode);assertEquals(0,site.hourlyWageCents);
        assertEquals(16000L,s.accounts.get(Construction.account(site.id)));assertEquals(0,site.completedHours);assertNull(owner.pending);
        minutes(s,60);assertEquals(0,site.completedHours);assertEquals("construction",s.buildings.get(site.id).type);
    }
    @Test void completedOwnedHomeStopsNewRentButStillPaysOldArrearsAndKeepsItsLoan() {
        State s=setup();String plot=clearPlot(s);State.Resident owner=s.agents.get("builder");owner.money=1000;
        Construction.start(s,owner,plot,"house","Owned Home",null,3,"self","home-loan",new Engine.Effects());
        Construction.Site site=s.construction.get("site:home-loan");site.requiredHours=1;atSite(s,owner.id,site.id);
        act(s,owner.id,ActionType.BUILD_CONSTRUCTION,site.id,null,null,null,"build-owned");minutes(s,60);
        assertEquals(site.id,owner.home);assertEquals("completed",site.status);assertEquals(15000,s.loans.get("loan:home-loan").principalCents);
        assertEquals(0,s.loans.get("loan:home-loan").paidCents);owner.arrears=750;long money=owner.money;
        s.minute=1439;Engine.Effects midnight=Engine.tick(s);assertEquals(money-750,owner.money);assertEquals(0,owner.arrears);
        assertEquals(750,midnight.payments.stream().filter(p->owner.id.equals(p.source())&&"rent".equals(p.reason())).mapToLong(Engine.Payment::amount).sum());
        money=owner.money;s.minute=2879;midnight=Engine.tick(s);assertEquals(money,owner.money);
        assertTrue(midnight.payments.stream().noneMatch(p->owner.id.equals(p.source())&&"rent".equals(p.reason())));
        assertEquals(17250,Loans.view(s,owner).outstandingCents());assertEquals("active",s.loans.get("loan:home-loan").status);
    }
}
