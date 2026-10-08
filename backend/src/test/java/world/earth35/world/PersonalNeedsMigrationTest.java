package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import db.migration.V10__entrances_hours_and_personal_needs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PersonalNeedsMigrationTest {
    @Test void savedWorldKeepsClockFinancesPlansInventoryHistoryAndAcceptedActions() throws Exception {
        State s=State.prototypeSeed();s.minute=1600;s.version=77;
        var walker=new State.Resident();walker.id="walker";walker.name="Walker";walker.home="apt-101";walker.location="road";walker.x=2.4;walker.z=2.4;
        walker.money=12345;walker.wetness=18;walker.inventory.put("raincoat",1);walker.nightPlan=new State.NightPlan("See a friend",1800);
        walker.pending=Engine.pending("keep-trip","MOVE","office",0);walker.pending.route.add(new double[]{18,-30});walker.pending.socialUntil=1620;
        s.agents.put(walker.id,walker);
        var sleeper=new State.Resident();sleeper.id="sleeper";sleeper.home="apt-102";sleeper.location=sleeper.home;sleeper.routine=new State.DailyRoutine();
        sleeper.pending=Engine.pending("keep-sleep","SLEEP",sleeper.home,1860);s.agents.put(sleeper.id,sleeper);
        s.conversations.put("history",new State.Conversation());s.accounts.put("escrow:keep-work",2000L);
        var json=new ObjectMapper();ObjectNode data=json.valueToTree(s);data.put("futureField","preserve");
        data.withObject("/agents/walker").put("privateMemory","preserve");
        var finances=data.get("accounts").deepCopy();var history=data.get("conversations").deepCopy();var stock=data.at("/buildings/market/stock").deepCopy();
        V10__entrances_hours_and_personal_needs.upgrade(data);
        assertEquals(1600,data.path("minute").asLong());assertEquals(78,data.path("version").asLong());
        assertEquals(finances,data.get("accounts"));assertEquals(history,data.get("conversations"));assertEquals(stock,data.at("/buildings/market/stock"));
        assertEquals("preserve",data.path("futureField").asText());assertEquals("preserve",data.at("/agents/walker/privateMemory").asText());
        assertEquals(12345,data.at("/agents/walker/money").asLong());assertEquals(1,data.at("/agents/walker/inventory/raincoat").asInt());
        assertEquals(1800,data.at("/agents/walker/nightPlan/until").asLong());assertEquals(1620,data.at("/agents/walker/pending/socialUntil").asLong());
        assertEquals("keep-trip",data.at("/agents/walker/pending/id").asText());assertTrue(data.at("/agents/walker/pending/frontDoorRoute").asBoolean());
        var route=data.at("/agents/walker/pending/route");assertEquals(-26.4,route.get(route.size()-1).get(1).asDouble(),.000001);
        assertEquals("keep-sleep",data.at("/agents/sleeper/pending/id").asText());assertEquals(1860,data.at("/agents/sleeper/pending/until").asLong());
        assertEquals(1380,data.at("/agents/sleeper/pending/startedAt").asLong());
        assertEquals(660,data.at("/jobs/job-bistro/startMinute").asInt());
        assertEquals("front",data.at("/buildings/office/entrance/side").asText());assertEquals(1200,data.at("/buildings/market/closeMinute").asInt());
    }
}
