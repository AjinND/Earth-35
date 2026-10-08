package world.earth35.world;

import java.util.*;

/** A small, deterministic city economy: daily supply and explicitly accounted operating support. */
public final class Economy {
    private Economy() {}
    public static final int SETTLEMENT_MINUTE=360;
    public static class Cycle {
        public long lastDay=-1,lastMinute=-1,subsidyToday,totalSubsidy;
    }
    public record Summary(long nextRestockMinute,long lastSettlementMinute,long subsidyTodayCents,long totalSubsidyCents) {}
    public static Summary summary(State s) {
        long day=Math.floorDiv(s.minute,1440),next=day*1440+SETTLEMENT_MINUTE;
        if(next<=s.minute&&s.economy.lastDay>=day)next+=1440;
        return new Summary(next,s.economy.lastMinute,s.economy.lastDay==day?s.economy.subsidyToday:0,s.economy.totalSubsidy);
    }
    public static int stockTarget(String item,int residents) {
        return switch(item){case "meal","coffee"->Math.max(100,residents*2);case "raincoat"->Math.max(30,residents);default->0;};
    }
    /** Resident startups pay from their own account even when sharing a city workplace. */
    public static String employer(State.Job job) {
        return job.employerAccount==null||job.employerAccount.isBlank()?job.building:job.employerAccount;
    }
    /** Retail buybacks and profit distributions preserve each employer's scheduled wages. */
    public static long remainingPayroll(State s,String account) {
        long day=Math.floorDiv(s.minute,1440);int minute=Math.floorMod(s.minute,1440);
        long reserved=0;
        for(State.Job job:s.jobs.values()) {
            if(!account.equals(employer(job))||minute>=job.endMinute||!job.workDays.contains((int)Math.floorMod(day,7)))continue;
            long paid=s.agents.values().stream().filter(a->job.id.equals(a.job)&&a.workDay==day).mapToLong(a->job.hourlyWage*(a.workedMinutes-a.overtimeMinutes)/60).sum();
            reserved+=Math.max(0,job.hourlyWage*job.capacity*((job.endMinute-job.startMinute)/60)-paid);
        }
        return reserved;
    }
    public static void settle(State s,Engine.Effects effects) {
        Loans.settle(s,effects);
        long day=Math.floorDiv(s.minute,1440);
        if(Math.floorMod(s.minute,1440)<SETTLEMENT_MINUTE||s.economy.lastDay>=day)return;
        s.economy.lastDay=day;s.economy.lastMinute=s.minute;s.economy.subsidyToday=0;
        // Recycle money collected by the city's homes, transport and wholesale supplier.
        for(String account:List.of("housing","transit","city-supply")) {
            long balance=s.accounts.getOrDefault(account,0L);
            if(balance>0)Engine.pay(s,account,"city-treasury",balance,"city_revenue",effects);
        }
        for(State.Building b:s.buildings.values()) {
            long wages=s.jobs.values().stream().filter(j->b.id.equals(j.building)&&b.id.equals(employer(j)))
                .mapToLong(j->j.hourlyWage*j.capacity*((j.endMinute-j.startMinute)/60)).sum();
            long supplyCost=0;
            for(var price:b.prices.entrySet()) {
                int missing=Math.max(0,stockTarget(price.getKey(),s.agents.size())-b.stock.getOrDefault(price.getKey(),0));
                supplyCost+=missing*Math.max(1,price.getValue()/2);
            }
            if(wages==0&&supplyCost==0)continue;
            long need=Math.max(0,wages+supplyCost-s.accounts.getOrDefault(b.id,0L));
            long recycled=Math.min(need,Math.max(0,s.accounts.getOrDefault("city-treasury",0L)));
            if(recycled>0)Engine.pay(s,"city-treasury",b.id,recycled,"operating_support",effects);
            long subsidy=need-recycled;
            // Issuance is bounded by one day's wages and actual missing supply, not a balance reset.
            // Its source account and transaction ledger retain the cumulative issued currency.
            if(subsidy>0){Engine.pay(s,"city-issuance",b.id,subsidy,"city_subsidy",effects);s.economy.subsidyToday+=subsidy;s.economy.totalSubsidy+=subsidy;}
            for(var price:b.prices.entrySet()) {
                int missing=Math.max(0,stockTarget(price.getKey(),s.agents.size())-b.stock.getOrDefault(price.getKey(),0));
                if(missing==0)continue;
                Engine.pay(s,b.id,"city-supply",missing*Math.max(1,price.getValue()/2),"restock:"+price.getKey(),effects);
                b.stock.merge(price.getKey(),missing,Integer::sum);
                effects.facts.add(new Engine.Fact("economy",b.id,"",b.name+" received "+missing+" "+price.getKey()+" for the new day."));
            }
        }
        effects.facts.add(new Engine.Fact("economy","","","New Eden completed its daily supply and operating cycle. City subsidy: "+s.economy.subsidyToday+" cents."));
    }
}
