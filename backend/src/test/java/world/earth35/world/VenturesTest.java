package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;

class VenturesTest {
    private State setup() {
        State s=State.prototypeSeed();s.minute=600;
        for(String id:List.of("founder","partner","buyer","worker","second-worker")) {
            State.Resident a=new State.Resident();a.id=id;a.name=id;a.home="apt-101";a.location="office";
            a.x=s.buildings.get("office").x;a.z=s.buildings.get("office").z;s.agents.put(id,a);
        }
        Projects.Project p=new Projects.Project();p.id="food-project";p.founder="founder";p.title="Useful field lunch";
        p.subject="business";p.problem="Residents need portable nourishment during their workday.";
        p.description="A nourishment product with a measured effect on hunger.";p.location="office";p.recipe="nourishment";
        p.members.addAll(List.of("founder","partner"));p.productId="product:"+p.id;p.status="tested";p.prototypesBuilt=1;
        Projects.ResearchFinding test=new Projects.ResearchFinding();test.id="actual-test";test.project=p.id;test.method="test";
        test.evidence.put("beforeHunger",50.0);test.evidence.put("afterHunger",10.0);test.evidence.put("unitsConsumed",1.0);
        p.research.put("founder:test",test);s.projects.put(p.id,p);
        Projects.Product product=new Projects.Product();product.id=p.productId;product.project=p.id;product.title=p.title;
        product.subject=p.subject;product.recipe=p.recipe;product.description=p.description;product.unitCost=600;product.effects=Projects.recipe(p.recipe).effects();
        s.products.put(product.id,product);s.agents.get("founder").inventory.put(product.id,1);
        return s;
    }
    private Engine.Effects apply(State s,String resident,ActionType action,String target,String item,Integer quantity,Long amount,String message,String id) {
        Engine.Effects e=new Engine.Effects();Engine.apply(s,s.agents.get(resident),new Command(id,action,target,message,item,quantity,amount),id,e);return e;
    }
    private Ventures.Business launch(State s) {
        apply(s,"founder",ActionType.ENDORSE_BUSINESS,"food-project",null,null,null,null,"endorse-a");
        apply(s,"partner",ActionType.ENDORSE_BUSINESS,"food-project",null,null,null,null,"endorse-b");
        apply(s,"founder",ActionType.LAUNCH_BUSINESS,"food-project",null,1,20000L,"Field Lunch","launch");
        return s.businesses.get("business:launch");
    }
    private long total(State s) {
        return s.accounts.values().stream().mapToLong(Long::longValue).sum()+s.agents.values().stream().mapToLong(a->a.money).sum();
    }
    private void minutes(State s,int count) {for(int i=0;i<count;i++)Engine.tick(s);}

    @Test void everyMemberSeparatelyConsentsAndLaunchPaysRealFeeAndCapitalWithNoFreeStock() {
        State s=setup();long total=total(s),personal=s.agents.get("founder").money;Engine.Effects e=new Engine.Effects();
        Ventures.endorse(s,s.agents.get("founder"),"food-project",e);
        assertThrows(Engine.Invalid.class,()->Ventures.launch(s,s.agents.get("founder"),"food-project","Field Lunch",1,20000L,"launch",e));
        assertTrue(s.businesses.isEmpty());assertEquals(personal,s.agents.get("founder").money);assertTrue(e.payments.isEmpty());
        assertThrows(Engine.Invalid.class,()->Ventures.endorse(s,s.agents.get("buyer"),"food-project",e));
        Ventures.endorse(s,s.agents.get("partner"),"food-project",e);
        Ventures.launch(s,s.agents.get("founder"),"food-project","Field Lunch",1,null,"launch",e);
        Ventures.Business b=s.businesses.get("business:launch");
        assertEquals(0,b.stock);assertEquals(Set.of("founder","partner"),b.owners);assertEquals(personal-25000,s.agents.get("founder").money);
        assertEquals(20000L,s.accounts.get(b.id));assertEquals(5000L,s.accounts.get("city-treasury"));
        assertEquals(1,s.agents.get("founder").inventory.get(b.productId));assertEquals(b.id,Economy.employer(s.jobs.get(b.jobId)));
        assertEquals("launched",s.projects.get("food-project").status);assertEquals(total,total(s));
        assertThrows(Engine.Invalid.class,()->Ventures.launch(s,s.agents.get("founder"),"food-project","Again",1,20000L,"again",e));
    }
    @Test void launchRequiresBuiltMeasuredTestAndFounderMoneyAndRejectsNullManagementAmounts() {
        State s=setup();Projects.Project p=s.projects.get("food-project");p.businessEndorsements.addAll(p.members);
        p.research.clear();assertThrows(Engine.Invalid.class,()->Ventures.launch(s,s.agents.get("founder"),p.id,"Field Lunch",1,20000L,"bad",new Engine.Effects()));
        Projects.ResearchFinding f=new Projects.ResearchFinding();f.method="test";p.research.put("test",f);
        s.agents.get("founder").money=24999;assertThrows(Engine.Invalid.class,()->Ventures.launch(s,s.agents.get("founder"),p.id,"Field Lunch",1,20000L,"bad",new Engine.Effects()));
        s.agents.get("founder").money=100000;Ventures.launch(s,s.agents.get("founder"),p.id,"Field Lunch",1,20000L,"launch",new Engine.Effects());
        for(ActionType action:List.of(ActionType.FUND_BUSINESS,ActionType.SET_PRODUCT_PRICE,ActionType.WITHDRAW_PROFIT))
            assertThrows(Engine.Invalid.class,()->apply(s,"founder",action,"business:launch",p.productId,null,null,null,"missing"));
    }
    @Test void actualTimedProductionCreatesStockAndPaidPurchaseCreatesUsableInventory() {
        State s=setup();Ventures.Business b=launch(s);long total=total(s),capital=s.accounts.get(b.id);
        assertThrows(Engine.Invalid.class,()->apply(s,"buyer",ActionType.BUY,b.id,b.productId,1,null,null,"empty"));
        Engine.Effects started=apply(s,"founder",ActionType.PRODUCE_PRODUCT,b.id,null,2,null,null,"produce");
        assertEquals(capital-1200,s.accounts.get(b.id));assertEquals(1200L,s.accounts.get("escrow:manufacture:produce"));
        assertEquals(1200,started.payments.getFirst().amount());minutes(s,59);
        assertEquals(0,b.stock);assertNotNull(s.agents.get("founder").pending);minutes(s,1);
        assertEquals(2,b.stock);assertNull(s.agents.get("founder").pending);assertFalse(s.accounts.containsKey("escrow:manufacture:produce"));
        assertEquals(1200,b.materialsExpenseCents);assertEquals(1200L,s.accounts.get("city-supply"));
        long buyerMoney=s.agents.get("buyer").money;apply(s,"buyer",ActionType.BUY,b.id,b.productId,1,null,null,"buy");
        assertEquals(buyerMoney-1200,s.agents.get("buyer").money);assertEquals(capital,s.accounts.get(b.id));assertEquals(1,b.stock);
        assertEquals(1,b.sales);assertEquals(1200,b.revenueCents);assertEquals(1,s.agents.get("buyer").inventory.get(b.productId));
        s.agents.get("buyer").hunger=70;apply(s,"buyer",ActionType.USE_PRODUCT,b.productId,null,null,null,null,"use");
        assertEquals(30,s.agents.get("buyer").hunger);assertFalse(s.agents.get("buyer").inventory.containsKey(b.productId));assertEquals(total,total(s));
    }
    @Test void productionRequiresAcceptedEmploymentAndOpenOnsiteWorkRefundsWholeInterruptedBatch() {
        State s=setup();Ventures.Business b=launch(s);long balance=s.accounts.get(b.id),total=total(s);
        assertThrows(Engine.Invalid.class,()->apply(s,"worker",ActionType.PRODUCE_PRODUCT,b.id,null,1,null,null,"outsider"));
        apply(s,"worker",ActionType.APPLY_JOB,b.jobId,null,null,null,null,"apply");
        assertThrows(Engine.Invalid.class,()->apply(s,"worker",ActionType.PRODUCE_PRODUCT,b.id,null,1,null,null,"offer-only"));
        apply(s,"worker",ActionType.ACCEPT_JOB,b.jobId,null,null,null,null,"accept");
        apply(s,"worker",ActionType.PRODUCE_PRODUCT,b.id,null,2,null,null,"interrupted");minutes(s,31);
        assertEquals(0,b.stock);s.agents.get("worker").location="park";Engine.Effects interrupted=Engine.tick(s);
        assertEquals(balance,s.accounts.get(b.id));assertEquals(0,b.stock);assertEquals(0,b.materialsExpenseCents);
        assertNull(s.agents.get("worker").pending);assertEquals("cancelled",interrupted.completions.getFirst().status());
        assertTrue(interrupted.payments.stream().anyMatch(p->"production_material_refund".equals(p.reason())));assertEquals(total,total(s));
        s.agents.get("founder").location="park";assertThrows(Engine.Invalid.class,()->apply(s,"founder",ActionType.PRODUCE_PRODUCT,b.id,null,1,null,null,"away"));
        s.agents.get("founder").location="office";State.Building workplace=s.buildings.get("office");workplace.openMinute=600;workplace.closeMinute=(int)s.minute+29;
        assertThrows(Engine.Invalid.class,()->apply(s,"founder",ActionType.PRODUCE_PRODUCT,b.id,null,1,null,null,"closing"));
    }
    @Test void materialReservationsCannotDoubleSpendAndSaveRestoreCompletesExactlyOnce() throws Exception {
        State s=setup();Ventures.Business b=launch(s);s.minute=1100;s.accounts.put(b.id,1000L);
        apply(s,"founder",ActionType.PRODUCE_PRODUCT,b.id,null,1,null,null,"batch");
        assertThrows(Engine.Invalid.class,()->apply(s,"partner",ActionType.PRODUCE_PRODUCT,b.id,null,1,null,null,"racing-batch"));
        minutes(s,10);ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);
        long total=total(restored);minutes(restored,20);Ventures.Business copy=restored.businesses.get(b.id);
        assertEquals(1,copy.stock);assertEquals(400L,restored.accounts.get(copy.id));assertEquals(600,copy.materialsExpenseCents);
        minutes(restored,10);assertEquals(1,copy.stock);assertEquals(total,total(restored));
    }
    @Test void hungerAndExhaustionPreventManufacturingAndRefundInterruptedMaterials() {
        State s=setup();Ventures.Business b=launch(s);State.Resident a=s.agents.get("founder");
        long balance=s.accounts.get(b.id),total=total(s);
        a.energy=0;assertThrows(Engine.Invalid.class,()->apply(s,a.id,ActionType.PRODUCE_PRODUCT,b.id,null,1,null,null,"exhausted"));
        a.energy=100;a.hunger=95;assertThrows(Engine.Invalid.class,()->apply(s,a.id,ActionType.PRODUCE_PRODUCT,b.id,null,1,null,null,"hungry"));
        a.hunger=10;apply(s,a.id,ActionType.PRODUCE_PRODUCT,b.id,null,1,null,null,"started");minutes(s,5);
        a.hunger=98;Engine.tick(s);assertNull(a.pending);assertEquals(0,b.stock);assertEquals(balance,s.accounts.get(b.id));
        a.hunger=10;apply(s,a.id,ActionType.PRODUCE_PRODUCT,b.id,null,1,null,null,"second");a.energy=0;Engine.tick(s);
        assertNull(a.pending);assertEquals(0,b.stock);assertEquals(balance,s.accounts.get(b.id));assertEquals(total,total(s));
    }
    @Test void aBatchMayCompleteExactlyAtClosingAndSameMinuteTicksDoNotInventWork() {
        State s=setup();Ventures.Business b=launch(s);s.minute=1100;
        State.Building workplace=s.buildings.get("office");workplace.openMinute=600;workplace.closeMinute=1130;
        apply(s,"founder",ActionType.PRODUCE_PRODUCT,b.id,null,1,null,null,"close");
        Ventures.tick(s,s.agents.get("founder"),new Engine.Effects());assertEquals(0,s.agents.get("founder").pending.workMinutes);
        minutes(s,29);Ventures.tick(s,s.agents.get("founder"),new Engine.Effects());assertEquals(29,s.agents.get("founder").pending.workMinutes);
        minutes(s,1);assertEquals(1,b.stock);assertNull(s.agents.get("founder").pending);
    }
    @Test void startupWagesUseOwnFundsAndCitySettlementNeverSubsidizesOrRestocksThem() {
        State s=setup();Ventures.Business b=launch(s);long total=total(s),host=s.accounts.get(b.location),capital=s.accounts.get(b.id);
        long cityReserve=Economy.remainingPayroll(s,b.location),startupReserve=Economy.remainingPayroll(s,b.id);assertEquals(8000,startupReserve);
        assertEquals(160000,cityReserve); // Existing ten-seat city job, without the startup's extra seat.
        apply(s,"worker",ActionType.APPLY_JOB,b.jobId,null,null,null,null,"apply");apply(s,"worker",ActionType.ACCEPT_JOB,b.jobId,null,null,null,null,"accept");
        assertThrows(Engine.Invalid.class,()->apply(s,"second-worker",ActionType.APPLY_JOB,b.jobId,null,null,null,null,"full"));
        long salary=s.agents.get("worker").money;apply(s,"worker",ActionType.WORK,null,null,null,null,null,"work");
        assertEquals(capital-1000,s.accounts.get(b.id));assertEquals(host,s.accounts.get(b.location));assertEquals(7000,Economy.remainingPayroll(s,b.id));
        minutes(s,60);assertEquals(salary+1000,s.agents.get("worker").money);assertEquals(total,total(s));
        s.minute=1800;s.accounts.put(b.id,0L);Engine.Effects settlement=new Engine.Effects();Economy.settle(s,settlement);
        assertEquals(0L,s.accounts.get(b.id));assertEquals(0,b.stock);
        assertTrue(settlement.payments.stream().noneMatch(p->b.id.equals(p.destination())));
        s.minute=1980;assertThrows(Engine.Invalid.class,()->apply(s,"worker",ActionType.WORK,null,null,null,null,null,"unfunded"));
        apply(s,"partner",ActionType.FUND_BUSINESS,b.id,null,null,9000L,null,"fund");
        apply(s,"worker",ActionType.WORK,null,null,null,null,null,"funded");assertEquals(8000L,s.accounts.get(b.id));
    }
    @Test void pricesBuyingAndEqualOwnerProfitAreGroundedInSalesAndPreserveCapitalAndPayroll() {
        State s=setup();Ventures.Business b=launch(s);long total=total(s);
        assertThrows(Engine.Invalid.class,()->apply(s,"buyer",ActionType.SET_PRODUCT_PRICE,b.id,b.productId,null,5000L,null,"unauthorized"));
        assertThrows(Engine.Invalid.class,()->apply(s,"founder",ActionType.WITHDRAW_PROFIT,b.id,null,null,2L,null,"capital-is-not-profit"));
        apply(s,"founder",ActionType.SET_PRODUCT_PRICE,b.id,b.productId,null,5000L,null,"price");
        apply(s,"founder",ActionType.PRODUCE_PRODUCT,b.id,null,4,null,null,"batch");minutes(s,120);
        s.agents.get("buyer").location="park";assertThrows(Engine.Invalid.class,()->apply(s,"buyer",ActionType.BUY,b.id,b.productId,4,null,null,"away"));
        s.agents.get("buyer").location=b.location;apply(s,"buyer",ActionType.BUY,b.id,b.productId,4,null,null,"sale");
        assertEquals(17600-Economy.remainingPayroll(s,b.id),Ventures.views(s,s.agents.get("founder")).getFirst().availableProfitCents());
        assertThrows(Engine.Invalid.class,()->apply(s,"founder",ActionType.WITHDRAW_PROFIT,b.id,null,null,1L,null,"unequal"));
        assertThrows(Engine.Invalid.class,()->apply(s,"founder",ActionType.WITHDRAW_PROFIT,b.id,null,null,17600L,null,"reserved"));
        long founder=s.agents.get("founder").money,partner=s.agents.get("partner").money;
        apply(s,"partner",ActionType.WITHDRAW_PROFIT,b.id,null,null,9600L,null,"distribution");
        assertEquals(founder+4800,s.agents.get("founder").money);assertEquals(partner+4800,s.agents.get("partner").money);
        assertEquals(28000L,s.accounts.get(b.id));assertEquals(9600,b.distributedCents);assertEquals(total,total(s));
        Ventures.BusinessView publicView=Ventures.views(s,s.agents.get("buyer")).getFirst();
        assertNull(publicView.balanceCents());assertNull(publicView.revenueCents());assertNull(publicView.contributedCents());assertNull(publicView.availableProfitCents());
        Ventures.BusinessView privateView=Ventures.views(s,s.agents.get("partner")).getFirst();assertEquals(20000,privateView.revenueCents());assertEquals(28000,privateView.balanceCents());
        s.minute=1100;apply(s,"founder",ActionType.WITHDRAW_PROFIT,b.id,null,null,8000L,null,"remaining-profit");
        assertEquals(20000L,s.accounts.get(b.id));assertEquals(total,total(s));
    }
    @Test void payrollAndAllOwnershipFinanceInventoryAndEndorsementsSurviveSerialization() throws Exception {
        State s=setup();Ventures.Business b=launch(s);apply(s,"partner",ActionType.FUND_BUSINESS,b.id,null,null,10000L,null,"fund");
        apply(s,"partner",ActionType.SET_PRODUCT_PRICE,b.id,b.productId,null,2400L,null,"price");apply(s,"partner",ActionType.PRODUCE_PRODUCT,b.id,null,1,null,null,"batch");minutes(s,30);
        apply(s,"buyer",ActionType.BUY,b.id,b.productId,1,null,null,"buy");ObjectMapper json=new ObjectMapper();
        State restored=json.readValue(json.writeValueAsString(s),State.class);Ventures.Business copy=restored.businesses.get(b.id);
        assertEquals(b.owners,copy.owners);assertEquals(30000,copy.contributedCents);assertEquals(2400,copy.priceCents);assertEquals(2400,copy.revenueCents);assertEquals(1,copy.sales);
        assertEquals(b.id,Economy.employer(restored.jobs.get(b.jobId)));assertEquals(1,restored.agents.get("buyer").inventory.get(b.productId));
        assertEquals(Set.of("founder","partner"),restored.projects.get(b.project).businessEndorsements);assertEquals(total(s),total(restored));
        assertEquals(Ventures.views(s,s.agents.get("partner")),Ventures.views(restored,restored.agents.get("partner")));
    }
}
