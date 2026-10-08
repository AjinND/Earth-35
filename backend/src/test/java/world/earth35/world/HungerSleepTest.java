package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class HungerSleepTest {
    Resident resident() {
        Resident a=new Resident();a.id="resident";a.home="apt-101";a.location=a.home;
        return a;
    }
    @Test void onlyARareSubsetOfPoorlyFedSleepEpisodesCanBeInterrupted() {
        Resident a=resident();a.hunger=97;a.lastMealMinute=600;
        int eligible=0;
        for(int day=0;day<500;day++) {
            long bedtime=1320+day*1440L;a.lastMealMinute=bedtime-720;
            double threshold=DailyRoutines.hungerWakeThreshold(a,bedtime);
            assertEquals(threshold,DailyRoutines.hungerWakeThreshold(a,bedtime));
            if(threshold<=100)eligible++;
        }
        assertTrue(eligible>=25&&eligible<=75,"Rare per-episode eligibility: "+eligible);
    }
    @Test void recentAdequateMealsAndLowBedtimeHungerProtectSleep() {
        Resident a=resident();a.lastMealMinute=1200;a.hungerAfterLastMeal=20;a.hunger=97;
        for(int i=0;i<1000;i++) {a.id="resident-"+i;assertEquals(101,DailyRoutines.hungerWakeThreshold(a,1320));}
        a.lastMealMinute=0;a.hunger=40;
        assertEquals(101,DailyRoutines.hungerWakeThreshold(a,1320));
    }
    @Test void aSmallMealCanQualifyButAnUnknownHistoryDoesNotInventMissedMeals() {
        Resident a=resident();a.hunger=97;a.lastMealMinute=1300;a.hungerAfterLastMeal=75;
        boolean found=false;
        for(int i=0;i<1000;i++) {a.id="resident-"+i;if(DailyRoutines.hungerWakeThreshold(a,1320)<=100)found=true;}
        assertTrue(found);
        a.lastMealMinute=-1;a.hunger=90;assertEquals(101,DailyRoutines.hungerWakeThreshold(a,1320));
    }
    @Test void aWellFedResidentSleepsThroughAnEntireNight() {
        State s=State.seed();s.minute=1320;Resident a=resident();s.agents.put(a.id,a);a.hunger=60;
        Engine.apply(s,a,new Command("dinner",ActionType.EAT,null,null,"meal",null,null),"dinner",new Engine.Effects());
        Engine.apply(s,a,new Command("sleep",ActionType.SLEEP,null,null,null,8,null),"sleep",new Engine.Effects());
        long end=a.pending.until;
        while(s.minute<end-1) {Engine.tick(s);assertNotNull(a.pending);}
        Engine.tick(s);assertNull(a.pending);assertFalse(a.hungerWakeNeedsFood);
    }
    @Test void aDrinkIsUsedAtHomeIfFoodIsGoneAndMissingSuppliesAreNotInvented() {
        State s=State.seed();Resident a=resident();a.hungerWakeNeedsFood=true;a.resumeSleepUntil=900;a.hunger=99;
        a.inventory.clear();a.inventory.put("coffee",2);
        Command drink=NightRoutine.defaultAction(s,a,"drink");assertEquals("coffee",drink.item());
        Engine.apply(s,a,drink,"drink",new Engine.Effects());
        assertEquals(1,a.inventory.get("coffee"));assertFalse(a.hungerWakeNeedsFood);
        assertEquals(-1,a.lastMealMinute);assertEquals(ActionType.SLEEP,NightRoutine.defaultAction(s,a,"sleep").action());
        a.hungerWakeNeedsFood=true;a.inventory.clear();assertNull(NightRoutine.defaultAction(s,a,"empty"));
        a.paused=true;a.inventory.put("meal",3);assertNull(NightRoutine.defaultAction(s,a,"paused"));
    }
    @Test void suppliesCoverTheDayAndAdditionalHungerWithDeficitsForBothRuntimes() {
        Resident a=resident();a.inventory.clear();a.inventory.put("meal",1);a.inventory.put("coffee",1);
        var supplies=DailyRoutines.supplies(a);
        assertEquals(3,supplies.mealTarget());assertEquals(2,supplies.mealsToBuy());assertEquals(2,supplies.drinksToBuy());
        a.hunger=100;assertEquals(5,DailyRoutines.supplies(a).mealTarget());
        a.inventory.put("meal",10);a.inventory.put("coffee",10);
        assertEquals(0,DailyRoutines.supplies(a).mealsToBuy());assertEquals(0,DailyRoutines.supplies(a).drinksToBuy());
    }
    @Test void savedMealHistoryAndInterruptedSleepSurviveRestartAndLegacyDefaultsAreSafe() throws Exception {
        ObjectMapper json=new ObjectMapper();Resident a=resident();a.lastMealMinute=1234;a.hungerAfterLastMeal=12;
        a.resumeSleepUntil=1800;a.hungerWakeUntil=1800;a.hungerWakeNeedsFood=true;
        Resident restored=json.readValue(json.writeValueAsString(a),Resident.class);
        assertEquals(1234,restored.lastMealMinute);assertEquals(12,restored.hungerAfterLastMeal);
        assertEquals(1800,restored.resumeSleepUntil);assertEquals(1800,restored.hungerWakeUntil);assertTrue(restored.hungerWakeNeedsFood);
        Resident legacy=json.readValue("{}",Resident.class);assertEquals(-1,legacy.lastMealMinute);
        assertEquals(101,json.readValue("{\"type\":\"SLEEP\"}",Pending.class).hungerWakeThreshold);
    }
}
