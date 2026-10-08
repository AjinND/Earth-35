package world.earth35.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

/** Verify product use through elapsed world mechanics, beyond checking effect timer fields. */
class ProjectEffectsTest {
    private Resident resident(State s,String id,String home,String location) {
        Resident a=new Resident();a.id=id;a.name=id;a.home=home;place(s,a,location);s.agents.put(id,a);return a;
    }
    private void place(State s,Resident a,String location) {
        Building b=s.buildings.get(location);a.location=location;a.x=b.x;a.z=b.z;
    }
    private void act(State s,Resident a,ActionType type,String target,Integer quantity) {
        String id=UUID.randomUUID().toString();Engine.apply(s,a,new Command(id,type,target,null,null,quantity,null),id,new Engine.Effects());
    }
    /** Owned product fixture represents a manufactured unit; ProjectsTest covers its construction. */
    private Projects.Product product(State s,Resident a,String recipe) {
        Projects.Recipe r=Projects.recipe(recipe);Projects.Product p=new Projects.Product();p.id="product:"+recipe;
        p.title=r.title();p.recipe=recipe;p.effects=r.effects();p.unitCost=r.unitCost();s.products.put(p.id,p);a.inventory.put(p.id,1);return p;
    }
    private void ticks(State s,int minutes) {for(int i=0;i<minutes;i++)Engine.tick(s);}

    @Test void studyAidAcceleratesActualReadingWithoutInventingMinutesOrUnboundedMasteryAndExpires() {
        State s=State.seed();s.minute=540;
        Resident supported=resident(s,"supported","apt-101","library"),ordinary=resident(s,"ordinary","apt-102","library");
        for(Resident a:List.of(supported,ordinary)) {
            act(s,a,ActionType.BORROW_BOOK,"garden-basics",null);act(s,a,ActionType.BORROW_BOOK,"soil-and-seasons",null);place(s,a,a.home);
        }
        Projects.Product aid=product(s,supported,"study-aid");act(s,supported,ActionType.USE_PRODUCT,aid.id,null);
        assertFalse(supported.inventory.containsKey(aid.id));assertTrue(supported.knowledge.isEmpty());
        assertEquals(660,supported.studyBoostUntil);
        for(Resident a:List.of(supported,ordinary))act(s,a,ActionType.READ_BOOK,"garden-basics",96);
        ticks(s,96);
        assertNull(supported.pending);assertNull(ordinary.pending);
        BookStudy accelerated=supported.bookStudies.get("garden-basics"),baseline=ordinary.bookStudies.get("garden-basics");
        assertEquals(96,accelerated.readingMinutes);assertEquals(120,accelerated.studyMinutes);
        assertEquals(96,baseline.readingMinutes);assertEquals(96,baseline.studyMinutes);
        assertEquals(96,supported.knowledge.get("gardening").readingMinutes);assertEquals(120,supported.knowledge.get("gardening").studyMinutes);
        assertEquals(8,supported.knowledge.get("gardening").points);assertEquals(6,ordinary.knowledge.get("gardening").points);
        assertEquals(4,supported.knowledge.get("gardening").concepts.size());
        for(Resident a:List.of(supported,ordinary))act(s,a,ActionType.READ_BOOK,"garden-basics",24);
        ticks(s,24);
        assertEquals(660,s.minute);assertEquals(120,accelerated.readingMinutes);assertEquals(120,accelerated.studyMinutes);
        assertEquals(8,supported.knowledge.get("gardening").points);assertEquals(4,supported.knowledge.get("gardening").concepts.size());
        assertEquals(120,baseline.studyMinutes);
        // Reading a different, unmastered book after the timer expires receives ordinary credit.
        for(Resident a:List.of(supported,ordinary))act(s,a,ActionType.READ_BOOK,"soil-and-seasons",40);
        ticks(s,40);
        assertTrue(s.minute>supported.studyBoostUntil);
        assertEquals(40,supported.bookStudies.get("soil-and-seasons").readingMinutes);
        assertEquals(40,supported.bookStudies.get("soil-and-seasons").studyMinutes);
        assertEquals(ordinary.bookStudies.get("soil-and-seasons").studyMinutes,supported.bookStudies.get("soil-and-seasons").studyMinutes);
        assertEquals(160,supported.knowledge.get("gardening").readingMinutes);assertEquals(160,supported.knowledge.get("gardening").studyMinutes);
    }

    @Test void weatherCareSuppressesActualRainExposureForItsFiniteDurationThenWetnessReturns() {
        State s=State.seed();
        long rainyStart=480;
        while(!Engine.weather(rainyStart).equals("Rain")||!Engine.weather(rainyStart+123).equals("Rain"))rainyStart+=60;
        s.minute=rainyStart;
        Resident protectedResident=resident(s,"protected","apt-101","park"),unprotected=resident(s,"unprotected","apt-102","park");
        assertFalse(Travel.sheltered(s,protectedResident));assertFalse(Travel.sheltered(s,unprotected));
        protectedResident.wetness=90;unprotected.wetness=30;
        Projects.Product care=product(s,protectedResident,"weather-care");act(s,protectedResident,ActionType.USE_PRODUCT,care.id,null);
        assertEquals(30,protectedResident.wetness);assertEquals(rainyStart+120,protectedResident.weatherProtectionUntil);
        assertFalse(protectedResident.inventory.containsKey(care.id));assertFalse(protectedResident.raincoatWorn);
        assertTrue(Travel.situation(s,protectedResident).rainProtected());
        assertFalse(Travel.situation(s,unprotected).rainProtected());
        Engine.tick(s);assertEquals("Rain",Engine.weather(s.minute));
        assertEquals(28,protectedResident.wetness);assertEquals(31,unprotected.wetness);
        ticks(s,118);
        assertEquals(protectedResident.weatherProtectionUntil-1,s.minute);assertEquals(0,protectedResident.wetness);assertEquals(100,unprotected.wetness);
        Engine.tick(s);assertEquals(protectedResident.weatherProtectionUntil,s.minute);assertEquals("Rain",Engine.weather(s.minute));
        assertEquals(1,protectedResident.wetness);
        assertFalse(Travel.situation(s,protectedResident).rainProtected());
        ticks(s,3);assertEquals(4,protectedResident.wetness);assertEquals(100,unprotected.wetness);
    }
}
