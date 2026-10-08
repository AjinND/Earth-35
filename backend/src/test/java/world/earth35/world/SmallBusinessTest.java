package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;

class SmallBusinessTest {
    private State setup() {
        State s=State.prototypeSeed();s.minute=600;
        State.Building host=new State.Building("public-shop","Public market workshop","market","Downtown",0,0,20);
        host.openMinute=480;host.closeMinute=1200;s.buildings.put(host.id,host);
        for(String id:List.of("owner","buyer","worker")) {
            State.Resident a=new State.Resident();a.id=id;a.name=id;a.home="apt-101";a.location=host.id;
            s.agents.put(id,a);
        }
        return s;
    }
    private String start(State s,String kind,Integer seats,Long capital,String id) {
        return Engine.apply(s,s.agents.get("owner"),new Command(id,ActionType.START_BUSINESS,"public-shop","Neighborhood "+kind,kind,seats,capital),id,new Engine.Effects());
    }
    private Engine.Effects apply(State s,String actor,ActionType action,String target,String item,Integer quantity,Long amount,String id) {
        Engine.Effects e=new Engine.Effects();
        Engine.apply(s,s.agents.get(actor),new Command(id,action,target,null,item,quantity,amount),id,e);return e;
    }
    private void minutes(State s,int count) {for(int i=0;i<count;i++)Engine.tick(s);}
    private long total(State s) {
        return s.accounts.values().stream().mapToLong(Long::longValue).sum()+s.agents.values().stream().mapToLong(a->a.money).sum();
    }

    @Test void soloShopsNeedNoResearchOrEmploymentAndPayActualRegistrationAndCapital() {
        for(String kind:List.of("bakery","fruit-shop")) {
            State s=setup();State.Resident owner=s.agents.get("owner");long money=owner.money,total=total(s);
            assertTrue(s.projects.isEmpty());assertTrue(owner.creations.isEmpty());assertTrue(owner.job.isEmpty());
            String result=start(s,kind,null,2000L,"open");Ventures.Business b=s.businesses.get("business:open");
            assertEquals(kind,b.kind);assertEquals(Set.of(owner.id),b.owners);assertEquals("",b.project);assertEquals(0,b.stock);
            assertEquals(0,s.jobs.get(b.jobId).capacity);assertEquals(b.id,Economy.employer(s.jobs.get(b.jobId)));
            assertEquals(0,Economy.remainingPayroll(s,b.id));assertEquals(2000L,s.accounts.get(b.id));assertEquals(money-7000,owner.money);
            assertEquals(5000L,s.accounts.get("city-treasury"));assertEquals(total,total(s));assertTrue(owner.job.isEmpty());
            assertTrue(result.contains("WITHDRAW_PROFIT"));assertTrue(s.projects.isEmpty());
            assertEquals(kind,Ventures.views(s,owner).getFirst().kind());
            assertThrows(Engine.Invalid.class,()->apply(s,"worker",ActionType.APPLY_JOB,b.jobId,null,null,null,"apply"));
        }
    }
    @Test void fruitShopStockRequiresFundedTimedWorkAndRealSalesCanSupportTheOwner() {
        State s=setup();start(s,"fruit-shop",0,2000L,"open");Ventures.Business b=s.businesses.get("business:open");long total=total(s);
        assertThrows(Engine.Invalid.class,()->apply(s,"owner",ActionType.WITHDRAW_PROFIT,b.id,null,null,1L,"capital"));
        apply(s,"owner",ActionType.SET_PRODUCT_PRICE,b.id,b.productId,null,900L,"price");
        apply(s,"owner",ActionType.PRODUCE_PRODUCT,b.id,null,2,null,"prepare");minutes(s,59);assertEquals(0,b.stock);
        minutes(s,1);assertEquals(2,b.stock);assertEquals(800,b.materialsExpenseCents);assertEquals(1200L,s.accounts.get(b.id));
        apply(s,"buyer",ActionType.BUY,b.id,b.productId,2,null,"sale");assertEquals(0,b.stock);assertEquals(1800,b.revenueCents);
        assertEquals(1000,Ventures.views(s,s.agents.get("owner")).getFirst().availableProfitCents());
        long personal=s.agents.get("owner").money;
        apply(s,"owner",ActionType.WITHDRAW_PROFIT,b.id,null,null,1000L,"income");
        assertEquals(personal+1000,s.agents.get("owner").money);assertEquals(2000L,s.accounts.get(b.id));
        s.agents.get("buyer").hunger=70;apply(s,"buyer",ActionType.USE_PRODUCT,b.productId,null,null,null,"eat");
        assertEquals(30,s.agents.get("buyer").hunger);assertEquals(1,s.agents.get("buyer").inventory.get(b.productId));assertEquals(total,total(s));
    }
    @Test void invalidOrUnfundedShopsDoNotReserveMoneyOrCreateAnything() {
        State s=setup();State.Resident owner=s.agents.get("owner");long total=total(s),money=owner.money;
        assertThrows(Engine.Invalid.class,()->start(s,"unlisted",0,2000L,"kind"));
        assertThrows(Engine.Invalid.class,()->start(s,"fruit-shop",0,399L,"ingredients"));
        assertThrows(Engine.Invalid.class,()->start(s,"bakery",2,2000L,"payroll"));
        assertThrows(Engine.Invalid.class,()->start(s,"bakery",-1,2000L,"seats"));
        assertThrows(Engine.Invalid.class,()->start(s,"bakery",6,2000L,"seats"));
        assertThrows(Engine.Invalid.class,()->Ventures.startSmallBusiness(s,owner,"apt-101","bakery","Private home",0,2000L,"home",new Engine.Effects()));
        owner.location="park";assertThrows(Engine.Invalid.class,()->start(s,"bakery",0,2000L,"remote"));owner.location="public-shop";
        s.minute=1300;assertThrows(Engine.Invalid.class,()->start(s,"bakery",0,2000L,"closed"));s.minute=600;
        owner.money=6999;assertThrows(Engine.Invalid.class,()->start(s,"bakery",0,2000L,"cash"));owner.money=money;
        assertTrue(s.businesses.isEmpty());assertTrue(s.products.isEmpty());assertEquals(total,total(s));assertEquals(money,owner.money);
    }
    @Test void shopJobsUseOwnFundsAndCompletedCommercialBuildingsCanHostProduction() {
        State s=setup();s.buildings.get("public-shop").type="bakery";
        start(s,"bakery",1,10000L,"open");Ventures.Business b=s.businesses.get("business:open");
        assertEquals(8000,Economy.remainingPayroll(s,b.id));
        apply(s,"worker",ActionType.APPLY_JOB,b.jobId,null,null,null,"apply");
        apply(s,"worker",ActionType.ACCEPT_JOB,b.jobId,null,null,null,"accept");
        long salary=s.agents.get("worker").money;
        apply(s,"worker",ActionType.WORK,null,null,null,null,"work");minutes(s,60);
        assertEquals(salary+1000,s.agents.get("worker").money);assertEquals(9000L,s.accounts.get(b.id));
        apply(s,"owner",ActionType.PRODUCE_PRODUCT,b.id,null,1,null,"prepare");minutes(s,30);assertEquals(1,b.stock);
        s.minute=1800;s.accounts.put(b.id,0L);Economy.settle(s,new Engine.Effects());assertEquals(0L,s.accounts.get(b.id));assertEquals(1,b.stock);
    }
    @Test void shopOwnershipProductsAndIncomeStateSurviveRestoreWithoutAutomaticStock() throws Exception {
        State s=setup();start(s,"bakery",0,2000L,"open");Ventures.Business b=s.businesses.get("business:open");
        apply(s,"owner",ActionType.PRODUCE_PRODUCT,b.id,null,1,null,"batch");minutes(s,10);
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);long total=total(restored);
        minutes(restored,20);Ventures.Business copy=restored.businesses.get(b.id);assertEquals("bakery",copy.kind);assertEquals(1,copy.stock);
        assertEquals(1400L,restored.accounts.get(copy.id));assertEquals(600,copy.materialsExpenseCents);
        assertEquals("nourishment",restored.products.get(copy.productId).recipe);assertEquals(0,restored.jobs.get(copy.jobId).capacity);
        minutes(restored,30);assertEquals(1,copy.stock);assertEquals(total,total(restored));
        assertNull(Ventures.views(restored,restored.agents.get("buyer")).getFirst().balanceCents());
    }
}
