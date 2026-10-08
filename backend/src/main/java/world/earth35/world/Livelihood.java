package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Private facts and available paths, without choosing or ranking a resident's career. */
public final class Livelihood {
    private Livelihood() {}
    public record JobOption(String id,String title,String building,int vacancies,long hourlyWageCents,int startMinute,int endMinute,List<Integer> workDays) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="LivelihoodView")
    public record View(long dailyRentCents,List<JobOption> jobs,List<Ventures.SmallBusinessOption> smallBusinesses,Construction.View construction,Loans.View financing) {}
    public static View view(State s,Resident a) {
        return new View(2000,s.jobs.values().stream().map(j->{
            long occupied=s.agents.values().stream().filter(r->j.id.equals(r.job)||j.id.equals(r.offer)).count();
            return new JobOption(j.id,j.title,j.building,(int)Math.max(0,j.capacity-occupied),j.hourlyWage,j.startMinute,j.endMinute,List.copyOf(j.workDays));
        }).toList(),Ventures.catalog(),Construction.view(s),Loans.view(s,a));
    }
}
