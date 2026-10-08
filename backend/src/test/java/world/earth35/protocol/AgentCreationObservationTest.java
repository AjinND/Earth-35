package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import world.earth35.world.*;

class AgentCreationObservationTest {
    @Test void residentsSeeOwnDebtAndPublicCreationFactsWithoutExposingOthersFinances() throws Exception {
        var json=new ObjectMapper();var store=spy(new WorldStore(mock(JdbcTemplate.class),json));
        State state=State.seed();state.minute=720;
        var self=resident("alex");var other=resident("maya");
        state.agents.put(self.id,self);state.agents.put(other.id,other);
        state.loans.put("alex-loan",loan("alex-loan",self.id,12000));
        state.loans.put("maya-loan",loan("maya-loan",other.id,80000));
        Construction.initializeCitySites(state,new Engine.Effects());
        var object=new WorldObjects.PlacedObject();object.id="maya-board";object.owner=other.id;
        object.kind="signboard";object.text="Community notice";object.x=150;object.z=200;
        state.worldObjects.put(object.id,object);
        doReturn(state).when(store).read(anyBoolean());
        doNothing().when(store).save(any(State.class));
        doReturn(List.of()).when(store).events(anyLong(),anyInt(),anyString());

        var observation=new AgentService(store).observe(self.id,0);
        assertEquals(1,observation.livelihood().financing().loans().size());
        assertEquals("alex-loan",observation.livelihood().financing().loans().getFirst().id());
        assertEquals(12000,observation.livelihood().financing().outstandingCents());
        var observed=json.valueToTree(observation);
        assertTrue(observed.path("worldObjects").path("objects").isArray());
        assertEquals("maya-board",observed.path("worldObjects").path("objects").path(0).path("id").asText());
        assertFalse(observed.toString().contains("maya-loan"));
        assertTrue(observation.actions().contains(Contracts.ActionType.REPAY_LOAN));
        assertTrue(observation.actions().contains(Contracts.ActionType.PLACE_OBJECT));
        assertTrue(observation.actions().contains(Contracts.ActionType.MOVE_OBJECT));

        var publicWorld=json.valueToTree(store.snapshot());
        assertFalse(publicWorld.path("construction").path("sites").isEmpty());
        assertEquals("maya-board",publicWorld.path("objects").path(0).path("id").asText());
        assertFalse(publicWorld.toString().contains("principalCents"));
        assertFalse(publicWorld.toString().contains("outstandingCents"));
        assertFalse(publicWorld.toString().contains("alex-loan"));
        assertFalse(publicWorld.toString().contains("maya-loan"));
    }

    private static State.Resident resident(String id) {
        var a=new State.Resident();a.id=id;a.name=id;a.ownerId=id+"-owner";a.home="apt-101";
        a.location="park";return a;
    }
    private static Loans.Loan loan(String id,String borrower,long total) {
        var loan=new Loans.Loan();loan.id=id;loan.borrower=borrower;loan.purpose="construction";
        loan.termYears=3;loan.installments=36;loan.principalCents=total;loan.totalRepaymentCents=total;
        loan.startedMinute=720;loan.maturityMinute=720+3*Loans.YEAR_MINUTES;return loan;
    }
}
