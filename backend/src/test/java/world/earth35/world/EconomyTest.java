package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;

class EconomyTest {
    long total(State s){return s.accounts.values().stream().mapToLong(Long::longValue).sum()+s.agents.values().stream().mapToLong(a->a.money).sum();}
    long budget(State s){return s.jobs.values().stream().mapToLong(j->j.hourlyWage*j.capacity*((j.endMinute-j.startMinute)/60)).sum();}
    @Test void emptyBusinessesRecoverAndRevenueIsRecycledWithoutTouchingEscrow() throws Exception {
        State s=State.seed();s.minute=1800;
        s.accounts.replaceAll((id,value)->0L);s.accounts.put("housing",40000L);s.accounts.put("transit",250L);s.accounts.put("escrow:in-flight",2000L);
        s.buildings.values().forEach(b->b.stock.replaceAll((id,value)->0));
        long before=total(s);var effects=new Engine.Effects();Economy.settle(s,effects);
        assertEquals(before,total(s));assertEquals(2000,s.accounts.get("escrow:in-flight"));
        assertEquals(0,s.accounts.get("housing"));assertEquals(0,s.accounts.get("transit"));
        assertTrue(effects.payments.stream().anyMatch(p->p.reason().equals("operating_support")));
        assertTrue(effects.payments.stream().allMatch(p->p.amount()>0));
        assertEquals(s.economy.totalSubsidy,-s.accounts.get("city-issuance"));
        for(var b:s.buildings.values())for(String item:b.prices.keySet())assertEquals(Economy.stockTarget(item,0),b.stock.get(item));
        long subsidy=s.economy.totalSubsidy;State restored=new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(s),State.class);
        var duplicate=new Engine.Effects();Economy.settle(restored,duplicate);
        assertTrue(duplicate.payments.isEmpty());assertTrue(duplicate.facts.isEmpty());assertEquals(subsidy,restored.economy.totalSubsidy);
    }
    @Test void supplyRecoversForFiftyResidentsOverAYearWithBoundedFunding() {
        State s=State.seed();s.accounts.replaceAll((id,value)->0L);
        for(int i=0;i<50;i++){var a=new State.Resident();a.id="resident-"+i;a.name=a.id;a.home="apt-101";a.location="grocery";s.agents.put(a.id,a);}
        long initial=total(s),dailyBudget=budget(s);
        for(int day=1;day<=365;day++) {
            s.minute=day*1440L+360;var effects=new Engine.Effects();Economy.settle(s,effects);
            long supply=effects.payments.stream().filter(p->p.reason().startsWith("restock:")).mapToLong(Engine.Payment::amount).sum();
            assertTrue(s.economy.subsidyToday<=dailyBudget+supply);
            s.minute=day*1440L+480; // Buy when the grocery opens, after the morning restock.
            // Exercise real purchase/consumption mechanics, not direct inventory resets.
            for(var a:s.agents.values()) {
                Engine.apply(s,a,new Command("buy",ActionType.BUY,"grocery",null,"meal",2,null),"buy",effects);
                Engine.apply(s,a,new Command("eat",ActionType.EAT,null,null,"meal",1,null),"eat",effects);
                Engine.apply(s,a,new Command("eat2",ActionType.EAT,null,null,"meal",1,null),"eat2",effects);
            }
            assertEquals(0,s.buildings.get("grocery").stock.get("meal"));
            // Worst case: every available shift is fully worked. All wages are ordinary ledger transfers.
            int index=0;
            for(var j:s.jobs.values()) {
                long payroll=j.hourlyWage*j.capacity*((j.endMinute-j.startMinute)/60);
                assertTrue(s.accounts.getOrDefault(j.building,0L)>=payroll);
                Engine.pay(s,j.building,"resident-"+(index++%50),payroll,"wage",effects);
            }
            // Redistribute the synthetic worst-case payroll among residents for next day's purchases.
            long money=s.agents.values().stream().mapToLong(a->a.money).sum(),share=money/50,remainder=money%50;
            for(var a:s.agents.values()){a.money=share+(remainder-->0?1:0);Engine.pay(s,a.id,"housing",2000,"rent",effects);}
            assertEquals(initial,total(s));
            assertTrue(s.accounts.entrySet().stream().filter(e->!e.getKey().equals("city-issuance")).allMatch(e->e.getValue()>=0));
            assertTrue(s.agents.values().stream().allMatch(a->a.money>=0));
        }
        assertEquals(s.economy.totalSubsidy,-s.accounts.get("city-issuance"));
    }
    @Test void overstockAndBalancesArePreservedAndCycleWaitsUntilSix() {
        State s=State.seed();s.buildings.get("grocery").stock.put("meal",300);long balance=s.accounts.get("grocery");
        s.minute=1799;var effects=new Engine.Effects();Economy.settle(s,effects);assertTrue(effects.payments.isEmpty());
        Engine.tick(s);assertEquals(300,s.buildings.get("grocery").stock.get("meal"));assertEquals(balance,s.accounts.get("grocery"));
        assertEquals(3240,Economy.summary(s).nextRestockMinute());assertEquals(0,s.economy.totalSubsidy);
    }
    @Test void buybacksCannotDrainScheduledPayrollAndReservedShiftsAreCountedOnce() {
        State s=State.seed();s.minute=600;
        var a=new State.Resident();a.id="seller";a.name="Seller";a.location="grocery";s.agents.put(a.id,a);
        long reserve=Economy.remainingPayroll(s,"grocery");assertTrue(reserve>0);
        s.accounts.put("grocery",reserve);
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,new Command("sell",ActionType.SELL,"grocery",null,"meal",1,null),"sell",new Engine.Effects()));
        a.job="job-grocery";a.workDay=0;a.workedMinutes=60;
        assertEquals(reserve-2000,Economy.remainingPayroll(s,"grocery"));
        Engine.apply(s,a,new Command("sell",ActionType.SELL,"grocery",null,"meal",1,null),"sell",new Engine.Effects());
        assertTrue(s.accounts.get("grocery")>=Economy.remainingPayroll(s,"grocery"));
    }
}
