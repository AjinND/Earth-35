package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.IslandGeography.Point;

class WorldObjectsTest {
    private State setup() {
        State s=new State();s.geographyVersion=2;
        State.Resident a=new State.Resident();a.id="maker";a.name="Maker";a.home="home";a.location="road";s.agents.put(a.id,a);
        double[] plot=plot(s);a.x=plot[0]-3;a.z=plot[1];return s;
    }
    private double[] plot(State s) {
        for(int x=-500;x<200;x+=19)for(int z=-470;z<300;z+=19) {
            try {WorldObjects.requireClear(s,x,z,null,"maker");
                if(IslandGeography.dryLand(x-8,z)&&IslandGeography.dryLand(x+8,z)&&IslandGeography.dryLand(x,z+8))return new double[]{x,z};
            }catch(Engine.Invalid ignored) {}
        }
        throw new AssertionError("No clear sign plot");
    }
    private String point(double x,double z){return "point:"+x+","+z;}
    private Engine.Effects act(State s,ActionType type,String target,String item,String message,String id) {
        Engine.Effects e=new Engine.Effects();Engine.apply(s,s.agents.get("maker"),new Command(id,type,target,message,item,null,null),id,e);return e;
    }
    private void minutes(State s,int n){for(int i=0;i<n;i++)Engine.tick(s);}
    private long total(State s){return s.accounts.values().stream().mapToLong(Long::longValue).sum()+s.agents.values().stream().mapToLong(a->a.money).sum();}
    private String install(State s) {
        State.Resident a=s.agents.get("maker");act(s,ActionType.PLACE_OBJECT,point(a.x+3,a.z),"signboard","Community workshop ahead","sign");minutes(s,15);return "object:sign";
    }
    @Test void installationReservesRealMoneyAndRequiresTimedWorkSurvivingRestart() throws Exception {
        State s=setup();State.Resident a=s.agents.get("maker");long total=total(s),money=a.money;
        act(s,ActionType.PLACE_OBJECT,point(a.x+3,a.z),"signboard","Library this way","save");
        assertEquals(money-2500,a.money);assertEquals(2500,s.accounts.get("escrow:object:save"));assertTrue(Engine.busy(a));assertTrue(s.worldObjects.isEmpty());
        minutes(s,7);ObjectMapper json=new ObjectMapper();s=json.readValue(json.writeValueAsString(s),State.class);a=s.agents.get("maker");
        minutes(s,7);assertTrue(s.worldObjects.isEmpty());minutes(s,1);assertNull(a.pending);
        assertEquals("Library this way",s.worldObjects.get("object:save").text);assertEquals(1,WorldObjects.view(s).size());
        assertFalse(s.accounts.containsKey("escrow:object:save"));assertEquals(total,total(s));assertEquals(money-2500,a.money);
        minutes(s,1);assertEquals(1,s.worldObjects.size());assertEquals(2500,s.accounts.get("city-supply"));
    }
    @Test void interruptionRefundsInstallationWithoutCreatingSign() {
        for(String reason:List.of("paused","left","skipped","blocked")) {
            State s=setup();State.Resident a=s.agents.get("maker");long money=a.money;
            act(s,ActionType.PLACE_OBJECT,point(a.x+3,a.z),"signboard","A sign","cancel");minutes(s,3);
            switch(reason) {
                case "paused"->a.paused=true;
                case "left"->a.x-=10;
                case "skipped"->s.minute+=5;
                case "blocked"->{State.Resident other=new State.Resident();other.id="other";other.x=a.pending.objectX;other.z=a.pending.objectZ;s.agents.put(other.id,other);}
            }
            Engine.Effects e=Engine.tick(s);assertNull(a.pending);assertTrue(s.worldObjects.isEmpty());assertEquals(money,a.money);
            assertTrue(e.completions.stream().anyMatch(c->c.id().equals("cancel")&&c.status().equals("cancelled")));
            assertFalse(s.accounts.containsKey("escrow:object:cancel"));
        }
    }
    @Test void moveRetainsOldPlacementUntilWorkCompletesAndChecksOwnership() {
        State s=setup();String id=install(s);var o=s.worldObjects.get(id);double oldX=o.x,oldZ=o.z;
        act(s,ActionType.MOVE_OBJECT,id,point(oldX,oldZ+3),null,"move");minutes(s,9);assertEquals(oldZ,o.z);
        minutes(s,1);assertEquals(oldX,o.x);assertEquals(oldZ+3,o.z);assertEquals(2500,s.accounts.get("city-supply"));
        o.owner="someone-else";assertThrows(Engine.Invalid.class,()->act(s,ActionType.MOVE_OBJECT,id,point(oldX+3,oldZ),null,"steal"));
        assertEquals(oldZ+3,o.z);
    }
    @Test void rejectedPlacementsCannotChargeMoneyOrReserveWork() {
        State s=setup();State.Resident a=s.agents.get("maker");long money=a.money;
        var road=IslandGeography.ROADS.getFirst().get(10);a.x=road.x();a.z=road.z();
        assertThrows(Engine.Invalid.class,()->act(s,ActionType.PLACE_OBJECT,point(a.x,a.z),"signboard","Blocks road","road"));
        assertThrows(Engine.Invalid.class,()->act(s,ActionType.PLACE_OBJECT,"point:NaN,Infinity","signboard","Invalid","bad"));
        assertEquals(money,a.money);assertNull(a.pending);assertTrue(s.accounts.isEmpty());
        double[] plot=plot(s);a.x=plot[0]-3;a.z=plot[1];a.money=2499;
        assertThrows(Engine.Invalid.class,()->act(s,ActionType.PLACE_OBJECT,point(plot[0],plot[1]),"signboard","Poor","poor"));
        assertEquals(2499,a.money);assertNull(a.pending);
    }
    @Test void entrancesAcceptedRoutesAndConcurrentPlacementsStayClear() {
        State s=setup();double[] plot=plot(s);var a=s.agents.get("maker");
        State.Building house=new State.Building("home","Home","house","Test",plot[0],plot[1],1);s.buildings.put(house.id,house);
        assertThrows(Engine.Invalid.class,()->WorldObjects.requireClear(s,plot[0],plot[1]+3.6,null,a.id));s.buildings.clear();
        State.Resident walker=new State.Resident();walker.id="walker";walker.x=plot[0]-10;walker.z=plot[1];walker.pending=Engine.pending("walk","MOVE","point",0);
        walker.pending.route.add(new double[]{plot[0]+10,plot[1]});s.agents.put(walker.id,walker);
        assertThrows(Engine.Invalid.class,()->act(s,ActionType.PLACE_OBJECT,point(plot[0],plot[1]),"signboard","Blocked path","path"));s.agents.remove(walker.id);
        act(s,ActionType.PLACE_OBJECT,point(plot[0],plot[1]),"signboard","Reserved","reserved");
        assertThrows(Engine.Invalid.class,()->WorldObjects.requireClear(s,plot[0],plot[1],null,"other"));
        assertThrows(Engine.Invalid.class,()->WorldObjects.requireConstructionClear(s,plot[0],plot[1]));
    }
    @Test void advertisingRequiresAnActualOwnedBusiness() {
        State s=setup();State.Resident a=s.agents.get("maker");String target=point(a.x+3,a.z);
        assertThrows(Engine.Invalid.class,()->act(s,ActionType.PLACE_OBJECT,target,"advertisement:missing","Buy bread","bad"));
        Ventures.Business business=new Ventures.Business();business.id="business";business.owners.add(a.id);s.businesses.put(business.id,business);
        act(s,ActionType.PLACE_OBJECT,target,"advertisement:business","Buy bread","ad");minutes(s,15);
        var sign=s.worldObjects.get("object:ad");assertEquals("advertisement",sign.kind);assertEquals("business",sign.business);
    }
    @Test void installedSignsInvalidateNavigationCacheAndRoutesGoAroundThem() {
        State s=setup();var a=s.agents.get("maker");double[] plot=plot(s);a.x=plot[0]-8;a.z=plot[1];
        State.Building destination=IslandGeography.coordinate(point(plot[0]+8,plot[1]));assertNotNull(destination);
        var direct=IslandNavigation.path(s,a,destination);assertEquals(1,direct.size());
        a.x=plot[0]-3;install(s);a.x=plot[0]-8;
        var route=IslandNavigation.path(s,a,destination);assertTrue(route.size()>1);Point from=new Point(a.x,a.z);
        State.Building obstacle=new State.Building();obstacle.x=plot[0];obstacle.z=plot[1];
        for(double[] step:route){Point to=new Point(step[0],step[1]);assertFalse(IslandNavigation.intersects(from,to,obstacle,1));from=to;}
    }
}
