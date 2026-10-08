package world.earth35.world;

import java.util.*;
import world.earth35.world.State.Resident;

/** Purpose-bound resident finance, repaid from real balances on the world clock. */
public final class Loans {
    private Loans() {}
    public static final int ANNUAL_SIMPLE_INTEREST_BASIS_POINTS=500;
    public static final long YEAR_MINUTES=365L*1440, BORROWING_LIMIT_CENTS=1_000_000, LENDER_INITIAL_CAPITAL_CENTS=100_000_000;
    public static final String LENDER_ACCOUNT="city-development-lender";
    public static void initialize(State s,Engine.Effects e) {
        if(!s.accounts.containsKey(LENDER_ACCOUNT))Engine.pay(s,"development-lender-capital-issuance",LENDER_ACCOUNT,
            LENDER_INITIAL_CAPITAL_CENTS,"development_lender_capital",e);
    }
    public static class Loan {
        public String id,borrower,purpose,status="active";
        public long principalCents,interestCents,totalRepaymentCents,paidCents,startedMinute,maturityMinute;
        public int termYears,installments;
    }
    public record Offer(int termYears,int annualSimpleInterestBasisPoints,int installments) {}
    public record LoanView(String id,String purpose,long principalCents,long interestCents,long totalRepaymentCents,
                           long paidCents,long outstandingCents,long overdueCents,int termYears,int installments,
                           long installmentCents,long startedMinute,Long nextDueMinute,long maturityMinute,String status) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="FinancingView")
    public record View(List<Offer> offers,List<LoanView> loans,long outstandingCents,long overdueCents,long borrowingLimitCents) {}

    public static View view(State s,Resident a) {
        List<LoanView> loans=s.loans.values().stream().filter(l->a.id.equals(l.borrower)).map(l->{
            long overdue=overdue(l,s.minute),outstanding=outstanding(l);
            int next=Math.min(l.installments,paidInstallments(l)+1);
            return new LoanView(l.id,l.purpose,l.principalCents,l.interestCents,l.totalRepaymentCents,l.paidCents,
                outstanding,overdue,l.termYears,l.installments,ceilDiv(l.totalRepaymentCents,l.installments),
                l.startedMinute,outstanding==0?null:dueMinute(l,next),l.maturityMinute,
                outstanding==0?"repaid":overdue>0?"overdue":"active");
        }).toList();
        return new View(List.of(new Offer(3,ANNUAL_SIMPLE_INTEREST_BASIS_POINTS,36),new Offer(5,ANNUAL_SIMPLE_INTEREST_BASIS_POINTS,60)),
            loans,loans.stream().mapToLong(LoanView::outstandingCents).sum(),loans.stream().mapToLong(LoanView::overdueCents).sum(),BORROWING_LIMIT_CENTS);
    }
    private static long ceilDiv(long n,long d) {return n/d+(n%d==0?0:1);}
    private static long outstanding(Loan l) {return Math.max(0,l.totalRepaymentCents-l.paidCents);}
    private static long scheduled(Loan l,int installment) {return l.totalRepaymentCents*installment/l.installments;}
    private static long dueMinute(Loan l,int installment) {return l.startedMinute+ceilDiv((l.maturityMinute-l.startedMinute)*installment,l.installments);}
    private static int dueInstallments(Loan l,long minute) {
        if(minute<=l.startedMinute)return 0;
        return (int)Math.min(l.installments,(minute-l.startedMinute)*l.installments/(l.maturityMinute-l.startedMinute));
    }
    private static int paidInstallments(Loan l) {
        int paid=0;while(paid<l.installments&&scheduled(l,paid+1)<=l.paidCents)paid++;return paid;
    }
    private static long overdue(Loan l,long minute) {return Math.max(0,scheduled(l,dueInstallments(l,minute))-l.paidCents);}

    /** Call after every project validation; borrowing never legitimizes an invalid project. */
    public static String finance(State s,Resident a,long required,Integer loanYears,String purpose,String actionId,Engine.Effects e) {
        Engine.require(loanYears==null||loanYears==3||loanYears==5,"Choose an explicit loan term of three or five world years");
        Engine.require(required>0&&a.money>=0,"Project cost and personal balance must be valid");
        long shortfall=Math.max(0,required-a.money);
        if(shortfall==0)return null;
        Engine.require(loanYears!=null,"Insufficient funds; explicitly choose loanYears 3 or 5 to finance the project shortfall");
        Engine.require(shortfall<=BORROWING_LIMIT_CENTS,"Project shortfall exceeds the development borrowing limit");
        long interest=shortfall*ANNUAL_SIMPLE_INTEREST_BASIS_POINTS*loanYears/10000;
        long total=shortfall+interest;
        long debt=s.loans.values().stream().filter(l->a.id.equals(l.borrower)).mapToLong(Loans::outstanding).sum();
        Engine.require(total<=BORROWING_LIMIT_CENTS&&debt<=BORROWING_LIMIT_CENTS-total,"Outstanding repayment and project interest exceed the development borrowing limit");
        Engine.require(s.loans.values().stream().filter(l->a.id.equals(l.borrower)).noneMatch(l->overdue(l,s.minute)>0),
            "Repay overdue loan installments before taking another development loan");
        String id="loan:"+actionId;Engine.require(!s.loans.containsKey(id),"This financing action already has a loan");
        Engine.require(s.accounts.getOrDefault(LENDER_ACCOUNT,0L)>=shortfall,"The development lender has insufficient available capital");
        Loan l=new Loan();l.id=id;l.borrower=a.id;l.purpose=purpose;l.principalCents=shortfall;
        l.termYears=loanYears;l.installments=loanYears*12;l.startedMinute=s.minute;l.maturityMinute=s.minute+loanYears*YEAR_MINUTES;
        l.interestCents=interest;l.totalRepaymentCents=total;
        Engine.pay(s,LENDER_ACCOUNT,a.id,shortfall,"loan_disbursement:"+id,e);s.loans.put(id,l);
        e.facts.add(new Engine.Fact("loan",a.id,id,a.name+" arranged development financing for "+purpose+" over "+loanYears+" world years."));
        return id;
    }
    /** Catch up all elapsed installments without resetting debt or making balances negative. */
    public static void settle(State s,Engine.Effects e) {
        for(Loan l:s.loans.values()) {
            if(outstanding(l)==0){l.status="repaid";continue;}
            Resident a=s.agents.get(l.borrower);long due=overdue(l,s.minute);
            if(due>0&&a!=null) {
                long payment=Math.min(due,Math.max(0,a.money));
                if(payment>0){Engine.pay(s,a.id,LENDER_ACCOUNT,payment,"loan_installment:"+l.id,e);l.paidCents+=payment;}
            }
            String previous=l.status;l.status=outstanding(l)==0?"repaid":overdue(l,s.minute)>0?"overdue":"active";
            if(!Objects.equals(previous,l.status))e.facts.add(new Engine.Fact("loan",l.borrower,l.id,
                "Development financing for "+l.purpose+" is "+l.status+"."));
        }
    }
    public static String repay(State s,Resident a,String loanId,Long amount,Engine.Effects e) {
        Loan l=s.loans.get(loanId);Engine.require(l!=null&&a.id.equals(l.borrower),"Choose one of your loans from livelihood.financing.loans");
        long balance=outstanding(l),payment=amount==null?balance:amount;
        Engine.require(payment>0&&payment<=balance,"Repayment must be positive and cannot exceed the outstanding balance");
        Engine.require(a.money>=payment,"Insufficient money for this loan repayment");
        Engine.pay(s,a.id,LENDER_ACCOUNT,payment,"loan_repayment:"+l.id,e);l.paidCents+=payment;
        l.status=outstanding(l)==0?"repaid":overdue(l,s.minute)>0?"overdue":"active";
        e.facts.add(new Engine.Fact("loan",a.id,l.id,a.name+" made a development loan repayment; financing is "+l.status+"."));
        return "Loan repayment recorded: "+payment+" cents; remaining balance "+outstanding(l)+" cents";
    }
}
