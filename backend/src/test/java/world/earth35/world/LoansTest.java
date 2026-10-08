package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoansTest {
    private State setup() {
        State s=State.prototypeSeed();s.minute=600;
        for(String id:new String[]{"owner","other"}) {
            State.Resident a=new State.Resident();a.id=id;a.name=id;a.location="market";a.home="apt-101";s.agents.put(id,a);
        }
        return s;
    }
    private long total(State s) {return s.accounts.values().stream().mapToLong(Long::longValue).sum()+s.agents.values().stream().mapToLong(a->a.money).sum();}
    private Loans.Loan loan(State s,int years,long amount) {
        State.Resident a=s.agents.get("owner");a.money=0;
        Loans.finance(s,a,amount,years,"construction:house","build",new Engine.Effects());
        return s.loans.get("loan:build");
    }
    @Test void savingsPayNormallyAndOnlyExplicitThreeOrFiveYearShortfallCreatesDebt() {
        State s=setup();State.Resident a=s.agents.get("owner");a.money=9000;long total=total(s);Engine.Effects e=new Engine.Effects();
        assertNull(Loans.finance(s,a,8000,null,"business:shop","cash",e));assertTrue(s.loans.isEmpty());assertTrue(e.payments.isEmpty());
        assertThrows(Engine.Invalid.class,()->Loans.finance(s,a,8000,2,"business:shop","term",e));
        assertThrows(Engine.Invalid.class,()->Loans.finance(s,a,25000,null,"business:shop","implicit",e));
        assertEquals("loan:shop",Loans.finance(s,a,25000,3,"business:shop","shop",e));
        Loans.Loan l=s.loans.get("loan:shop");assertEquals(16000,l.principalCents);assertEquals(2400,l.interestCents);
        assertEquals(18400,l.totalRepaymentCents);assertEquals(36,l.installments);assertEquals(600+3*Loans.YEAR_MINUTES,l.maturityMinute);
        assertEquals(25000,a.money);assertEquals(total,total(s));assertEquals(1,e.payments.size());
        assertTrue(e.payments.getFirst().reason().startsWith("loan_disbursement:"));
        assertTrue(Loans.view(s,s.agents.get("other")).loans().isEmpty());
    }
    @Test void installmentsUseWorldTimeRemainOverdueWithoutCashAndNeverOverdraftOrRepeat() {
        State s=setup();Loans.Loan l=loan(s,3,20000);State.Resident a=s.agents.get("owner");
        Engine.pay(s,a.id,"construction:spent",a.money,"project_cost",new Engine.Effects());
        long firstDue=Loans.view(s,a).loans().getFirst().nextDueMinute();s.minute=firstDue-1;
        Economy.settle(s,new Engine.Effects());assertEquals(0,l.paidCents);assertEquals("active",l.status);
        s.minute=firstDue;Engine.Effects missed=new Engine.Effects();Economy.settle(s,missed);
        assertEquals("overdue",l.status);assertEquals(0,a.money);long scheduled=l.totalRepaymentCents/l.installments;
        assertEquals(scheduled,Loans.view(s,a).overdueCents());
        assertThrows(Engine.Invalid.class,()->Loans.finance(s,a,1000,5,"business:other","other",new Engine.Effects()));
        a.money=100;long bank=s.accounts.get(Loans.LENDER_ACCOUNT);Engine.Effects partial=new Engine.Effects();Economy.settle(s,partial);
        assertEquals(100,l.paidCents);assertEquals(0,a.money);assertEquals(bank+100,s.accounts.get(Loans.LENDER_ACCOUNT));
        assertEquals(scheduled-100,Loans.view(s,a).overdueCents());Economy.settle(s,new Engine.Effects());assertEquals(100,l.paidCents);
        a.money=scheduled-100;Economy.settle(s,new Engine.Effects());assertEquals(scheduled,l.paidCents);assertEquals("active",l.status);
        assertEquals(0,Loans.view(s,a).overdueCents());
    }
    @Test void fiveYearSavedLoanCatchesUpAllElapsedPaymentsAndMaturityDoesNotEraseDebt() throws Exception {
        State s=setup();Loans.Loan l=loan(s,5,10000);assertEquals(60,l.installments);assertEquals(2500,l.interestCents);
        State.Resident a=s.agents.get("owner");Engine.pay(s,a.id,"business:spent",a.money,"project_cost",new Engine.Effects());
        s.minute=l.maturityMinute;Loans.settle(s,new Engine.Effects());assertEquals("overdue",l.status);assertEquals(12500,Loans.view(s,a).overdueCents());
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);
        assertEquals(12500,Loans.view(restored,restored.agents.get("owner")).outstandingCents());
        restored.agents.get("owner").money=12500;long total=total(restored);Economy.settle(restored,new Engine.Effects());
        assertEquals("repaid",restored.loans.get(l.id).status);assertEquals(0,Loans.view(restored,restored.agents.get("owner")).outstandingCents());
        assertNull(Loans.view(restored,restored.agents.get("owner")).loans().getFirst().nextDueMinute());assertEquals(total,total(restored));
        long bank=restored.accounts.get(Loans.LENDER_ACCOUNT);Economy.settle(restored,new Engine.Effects());assertEquals(bank,restored.accounts.get(Loans.LENDER_ACCOUNT));
    }
    @Test void repaymentsRequireOwnershipAndActualMoneyAndPrepaymentsReduceFutureDues() {
        State s=setup();Loans.Loan l=loan(s,3,10000);State.Resident a=s.agents.get("owner");
        assertThrows(Engine.Invalid.class,()->Loans.repay(s,s.agents.get("other"),l.id,100L,new Engine.Effects()));
        assertThrows(Engine.Invalid.class,()->Loans.repay(s,a,l.id,0L,new Engine.Effects()));
        assertThrows(Engine.Invalid.class,()->Loans.repay(s,a,l.id,null,new Engine.Effects()));
        long bank=s.accounts.get(Loans.LENDER_ACCOUNT);Loans.repay(s,a,l.id,1000L,new Engine.Effects());
        assertEquals(1000,l.paidCents);assertEquals(bank+1000,s.accounts.get(Loans.LENDER_ACCOUNT));
        s.minute=l.startedMinute+Loans.YEAR_MINUTES/12;Loans.settle(s,new Engine.Effects());assertEquals(1000,l.paidCents);
        a.money=l.totalRepaymentCents-l.paidCents;Loans.repay(s,a,l.id,null,new Engine.Effects());assertEquals("repaid",l.status);
        assertThrows(Engine.Invalid.class,()->Loans.repay(s,a,l.id,1L,new Engine.Effects()));
    }
    @Test void lenderReserveIsFiniteInitializedOnceAndRecycledByRepayment() {
        State s=setup();State.Resident a=s.agents.get("owner");a.money=0;
        s.accounts.put(Loans.LENDER_ACCOUNT,999L);Engine.Effects e=new Engine.Effects();
        assertThrows(Engine.Invalid.class,()->Loans.finance(s,a,1000,3,"business:new","new",e));
        assertTrue(s.loans.isEmpty());assertEquals(0,a.money);assertTrue(e.payments.isEmpty());
        Loans.initialize(s,e);assertEquals(999L,s.accounts.get(Loans.LENDER_ACCOUNT));assertTrue(e.payments.isEmpty());
        s.accounts.put(Loans.LENDER_ACCOUNT,1000L);Loans.finance(s,a,1000,3,"business:new","new",e);
        assertEquals(0L,s.accounts.get(Loans.LENDER_ACCOUNT));Loans.repay(s,a,"loan:new",500L,e);
        assertEquals(500L,s.accounts.get(Loans.LENDER_ACCOUNT));
        State blank=new State();long total=total(blank);Loans.initialize(blank,new Engine.Effects());assertEquals(total,total(blank));
        assertEquals(Loans.LENDER_INITIAL_CAPITAL_CENTS,blank.accounts.get(Loans.LENDER_ACCOUNT));
    }
    @Test void financingInvalidBusinessesCannotIssueLoansOrCreateStockAndBorrowedCashFundsActualCosts() {
        State s=setup();State.Resident a=s.agents.get("owner");a.money=1000;Engine.Effects e=new Engine.Effects();long total=total(s);
        assertThrows(Engine.Invalid.class,()->Ventures.startSmallBusiness(s,a,"market","bakery","Funded Bakery",2,2000L,3,"invalid",e));
        assertTrue(s.loans.isEmpty());assertTrue(e.payments.isEmpty());assertTrue(s.businesses.isEmpty());
        Ventures.startSmallBusiness(s,a,"market","bakery","Funded Bakery",0,2000L,3,"valid",e);
        assertEquals(6000,s.loans.get("loan:valid").principalCents);assertEquals(0,a.money);assertEquals(2000L,s.accounts.get("business:valid"));
        assertEquals(0,s.businesses.get("business:valid").stock);assertEquals(total,total(s));
    }
}
