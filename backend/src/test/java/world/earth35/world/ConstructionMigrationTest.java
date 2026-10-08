package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import db.migration.V21__autonomous_livelihood_and_construction;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import world.earth35.protocol.Contracts.*;

class ConstructionMigrationTest {
    @Test void fundsExistingSitesAndPreservesPrivateStateUnknownFieldsAndInFlightActions() throws Exception {
        ObjectMapper json=new ObjectMapper();State state=State.seed();state.minute=600;
        state.construction.clear();state.accounts.keySet().removeIf(k->k.startsWith("construction-fund:")||k.equals("city-construction-issuance"));
        State.Resident a=new State.Resident();a.id="existing";a.name="Existing";a.home="apt-101";a.location="office";a.job="job-office";
        state.agents.put(a.id,a);Engine.apply(state,a,new Command("shift",ActionType.WORK,null,null,null,null,null),"shift",new Engine.Effects());
        ObjectNode saved=(ObjectNode)json.readTree(json.writeValueAsString(state));saved.withObject("/agents/existing").put("futurePrivateField","preserve me");saved.put("futureWorldField","also preserve");
        ObjectNode migrated=migrate(saved,json);
        assertEquals(saved.path("agents"),migrated.path("agents"));assertEquals(saved.path("buildings"),migrated.path("buildings"));
        assertEquals(saved.path("futureWorldField"),migrated.path("futureWorldField"));assertEquals(saved.path("projects"),migrated.path("projects"));
        assertEquals(10,migrated.path("construction").size());
        saved.path("accounts").fields().forEachRemaining(entry->assertEquals(entry.getValue(),migrated.path("accounts").path(entry.getKey())));
        long totalBefore=0,totalAfter=0;
        for(var n:saved.path("accounts"))totalBefore+=n.asLong();for(var n:migrated.path("accounts"))totalAfter+=n.asLong();
        assertEquals(totalBefore,totalAfter,"City capital issuance has an explicit counteraccount");
        assertEquals(migrated.path("accounts"),migrate(migrated,json).path("accounts"),"Reapplying initialization cannot duplicate project funding");
    }
    private ObjectNode migrate(ObjectNode saved,ObjectMapper json) throws Exception {
        Context context=mock(Context.class);Connection connection=mock(Connection.class);
        PreparedStatement query=mock(PreparedStatement.class),update=mock(PreparedStatement.class),insert=mock(PreparedStatement.class);
        ResultSet rows=mock(ResultSet.class);when(context.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT data::text FROM world_state WHERE id=1 FOR UPDATE")).thenReturn(query);
        when(connection.prepareStatement("UPDATE world_state SET data=?::jsonb WHERE id=1")).thenReturn(update);
        when(connection.prepareStatement("INSERT INTO transactions(world_minute,source,destination,amount,reason) VALUES(?,?,?,?,?)")).thenReturn(insert);
        when(query.executeQuery()).thenReturn(rows);when(rows.next()).thenReturn(true);when(rows.getString(1)).thenReturn(saved.toString());
        new V21__autonomous_livelihood_and_construction().migrate(context);
        var encoded=ArgumentCaptor.forClass(String.class);verify(update).setString(eq(1),encoded.capture());
        verify(insert,times(saved.path("construction").size()==0?10:0)).executeUpdate();
        return (ObjectNode)json.readTree(encoded.getValue());
    }
}
