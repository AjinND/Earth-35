package world.earth35.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class EngineTest {
    @Test void runningUsesSameRouteAtDoubleSpeedAndCostsEnergy() {
        State walk=State.prototypeSeed(),run=State.prototypeSeed();
        Resident a=resident(walk,"a","apt-101"),b=resident(run,"b","apt-101");
        Engine.apply(walk,a,cmd(ActionType.MOVE,"park"),"walk",new Engine.Effects());
        Engine.apply(run,b,cmd(ActionType.RUN,"park"),"run",new Engine.Effects());
        double x=a.x,z=a.z,energy=b.energy;
        Engine.tick(walk);Engine.tick(run);
        // A tick can include a corner, so measure distance along the orthogonal route.
        assertEquals(2,Math.abs(a.x-x)+Math.abs(a.z-z),.001);assertEquals(3.6,Math.abs(b.x-x)+Math.abs(b.z-z),.001); // RUN stops at the exit door before turning.
        assertEquals(energy-.225,b.energy,.001);
        int walkingTicks=1,runningTicks=1;
        while(a.pending!=null){Engine.tick(walk);walkingTicks++;}
        while(b.pending!=null){Engine.tick(run);runningTicks++;}
        assertTrue(runningTicks<walkingTicks);assertEquals(a.x,b.x);assertEquals(a.z,b.z);assertEquals("park",b.location);
    }
    @Test void runningPreservesAccessAndEnergyRules() {
        State s=State.seed();Resident a=resident(s,"a","apt-101");
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.RUN,"apt-102"),"x",new Engine.Effects()));
        a.energy=9;assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.RUN,"park"),"x",new Engine.Effects()));
        a.energy=10;Engine.apply(s,a,cmd(ActionType.RUN,"park"),"x",new Engine.Effects());a.energy=.5;
        Engine.tick(s);assertEquals("MOVE",a.pending.type);assertTrue(a.energy>=0);
    }
    @Test void jumpStaysInPlaceCostsEnergyAndCompletesExactlyOnce() {
        State s=State.seed();Resident a=resident(s,"a","park");double x=a.x,z=a.z,energy=a.energy;
        Engine.apply(s,a,cmd(ActionType.JUMP,null),"jump",new Engine.Effects());
        assertEquals("JUMP",Engine.activity(a));assertEquals(energy-2,a.energy);assertEquals("park",a.location);
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.MOVE,"coffee"),"move",new Engine.Effects()));
        assertEquals(1,Engine.tick(s).completions.size());assertNull(a.pending);assertEquals(x,a.x);assertEquals(z,a.z);
        assertTrue(Engine.tick(s).completions.isEmpty());a.energy=1;
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.JUMP,null),"again",new Engine.Effects()));
    }
    @Test void weatherVariesAndStaysStableWithinEachFront() {
        var conditions=new java.util.HashSet<String>();
        for(long minute=0;minute<1440*7;minute+=360) {
            String weather=Engine.weather(minute);conditions.add(weather);
            assertEquals(weather,Engine.weather(minute+359));
            assertEquals(weather,Engine.weather(minute));
        }
        assertEquals(java.util.Set.of("Clear","Overcast","Rain"),conditions);
    }
    @Test void weatherFactsOccurOnceOnlyWhenConditionsChange() {
        State s=State.seed();s.minute=0;
        for(int i=0;i<1440*3;i++) {
            String previous=Engine.weather(s.minute);
            var facts=Engine.tick(s).facts.stream().filter(f->f.kind().equals("weather")).toList();
            assertEquals(previous.equals(Engine.weather(s.minute))?0:1,facts.size());
            if(!facts.isEmpty()) {assertEquals("",facts.getFirst().actor());assertEquals("",facts.getFirst().target());}
        }
    }
    @Test void temperatureReflectsTimeAndWeatherAndRemainsBounded() {
        assertTrue(Engine.temperature(900)>Engine.temperature(180));
        for(long minute=0;minute<1440*14;minute++) {
            int temperature=Engine.temperature(minute);
            assertTrue(temperature>=14&&temperature<=26);
            assertEquals(temperature,Engine.temperature(minute));
        }
    }
    Resident resident(State s,String id,String location) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=location;
        Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(id,a);return a;
    }
    Command cmd(ActionType type,String target) {return new Command("request",type,target,null,null,null,null);}
    @Test void movementUsesRoutesAndCompletes() {
        State s=State.seed();Resident a=resident(s,"a","apt-101");
        Engine.apply(s,a,cmd(ActionType.MOVE,"park"),"action",new Engine.Effects());
        assertEquals("road",a.location);assertNotNull(a.pending);
        for(int i=0;i<300;i++) Engine.tick(s);
        assertNull(a.pending);assertEquals("park",a.location);assertEquals(s.buildings.get("park").x,a.x);assertEquals(s.buildings.get("park").z,a.z);
    }
    @Test void rejectsPrivateHomeAndConcurrentActions() {
        State s=State.seed();Resident a=resident(s,"a","apt-101");
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.MOVE,"apt-102"),"x",new Engine.Effects()));
        Engine.apply(s,a,cmd(ActionType.SLEEP,null),"x",new Engine.Effects());
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.MOVE,"park"),"y",new Engine.Effects()));
    }
    @Test void wageReservedAndPaidExactlyOnce() {
        State s=State.seed();Resident a=resident(s,"a","office");
        s.minute=540;
        Engine.apply(s,a,cmd(ActionType.APPLY_JOB,"job-office"),"apply",new Engine.Effects());
        Engine.apply(s,a,cmd(ActionType.ACCEPT_JOB,"job-office"),"accept",new Engine.Effects());
        long employer=s.accounts.get("office"),money=a.money;
        Engine.apply(s,a,cmd(ActionType.WORK,null),"work",new Engine.Effects());
        assertEquals(employer-2000,s.accounts.get("office"));assertEquals(money,a.money);
        for(int i=0;i<120;i++) Engine.tick(s);
        assertEquals(money+2000,a.money);assertFalse(s.accounts.containsKey("escrow:work"));
    }
    @Test void tradeConservesMoneyAndInventory() {
        State s=State.seed();Resident a=resident(s,"a","grocery");long total=a.money+s.accounts.get("grocery");
        Engine.apply(s,a,new Command("1",ActionType.BUY,"grocery",null,"meal",3,null),"x",new Engine.Effects());
        assertEquals(5,a.inventory.get("meal"));assertEquals(97,s.buildings.get("grocery").stock.get("meal"));
        assertEquals(total,a.money+s.accounts.get("grocery"));
        a.money=0;
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,new Command("2",ActionType.BUY,"grocery",null,"meal",1,null),"y",new Engine.Effects()));
        assertEquals(97,s.buildings.get("grocery").stock.get("meal"));
    }
    @Test void transfersRequireProximityAndFunds() {
        State s=State.seed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        Engine.apply(s,a,new Command("1",ActionType.TRANSFER,"b",null,null,null,2000L),"x",new Engine.Effects());
        assertEquals(98000,a.money);assertEquals(102000,b.money);
        b.x=100;
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,new Command("2",ActionType.TRANSFER,"b",null,null,null,2000L),"y",new Engine.Effects()));
    }
    @Test void rentCreatesArrearsWithoutNegativeBalances() {
        State s=State.seed();Resident a=resident(s,"a","apt-101");s.minute=1439;a.money=300;
        Engine.tick(s);assertEquals(0,a.money);assertEquals(1700,a.arrears);assertEquals(300,s.accounts.get("housing"));
    }
    @Test void jobOffersReserveFiniteVacancies() {
        State s=State.seed();s.jobs.get("job-office").capacity=1;
        Resident a=resident(s,"a","office"),b=resident(s,"b","office");
        Engine.apply(s,a,cmd(ActionType.APPLY_JOB,"job-office"),"a",new Engine.Effects());
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,b,cmd(ActionType.APPLY_JOB,"job-office"),"b",new Engine.Effects()));
    }
    @Test void fiftyResidentsRemainBoundedAndDeterministic() {
        State a=State.seed(),b=State.seed();
        for(int i=0;i<50;i++) {resident(a,"a"+i,"park");resident(b,"a"+i,"park");}
        for(int i=0;i<4320;i++) {Engine.tick(a);Engine.tick(b);}
        assertEquals(a.minute,b.minute);
        for(String id:a.agents.keySet()) {assertEquals(a.agents.get(id).hunger,b.agents.get(id).hunger);assertTrue(a.agents.get(id).energy>=0);assertTrue(a.agents.get(id).hunger<=100);}
    }
}
