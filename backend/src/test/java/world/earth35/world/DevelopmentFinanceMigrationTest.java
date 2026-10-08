package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import db.migration.V22__development_financing;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DevelopmentFinanceMigrationTest {
    @Test void addsFiniteLenderAndLaborDefaultsWithoutChangingExistingResidentsActionsOrUnknownFields() throws Exception {
        ObjectMapper json=new ObjectMapper();State s=State.seed();s.minute=610;
        State.Resident a=new State.Resident();a.id="old";a.name="Old";a.home="apt-101";a.location="road";
        a.pending=new State.Pending();a.pending.id="old-action";a.pending.type="BUILD_CONSTRUCTION";a.pending.target="construction-201";a.pending.workMinutes=10;
        s.agents.put(a.id,a);ObjectNode saved=(ObjectNode)json.readTree(json.writeValueAsString(s));
        saved.remove("loans");((ObjectNode)saved.path("accounts")).remove(Loans.LENDER_ACCOUNT);
        ((ObjectNode)saved.path("accounts")).remove("development-lender-capital-issuance");
        saved.path("construction").forEach(site->((ObjectNode)site).remove("laborMode"));
        saved.withObject("/agents/old").put("futurePrivateValue","keep");saved.put("futureWorldValue","keep too");
        ObjectNode migrated=migrate(saved,json,1);
        assertEquals(saved.path("agents"),migrated.path("agents"));assertEquals(saved.path("buildings"),migrated.path("buildings"));
        assertEquals(saved.path("futureWorldValue"),migrated.path("futureWorldValue"));assertTrue(migrated.path("loans").isEmpty());
        assertEquals("keep",migrated.path("agents").path("old").path("futurePrivateValue").asText());
        assertEquals("keep too",migrated.path("futureWorldValue").asText());
        assertEquals(Loans.LENDER_INITIAL_CAPITAL_CENTS,migrated.path("accounts").path(Loans.LENDER_ACCOUNT).asLong());
        saved.path("accounts").fields().forEachRemaining(entry->assertEquals(entry.getValue(),migrated.path("accounts").path(entry.getKey())));
        assertEquals(sum(saved.path("accounts")),sum(migrated.path("accounts")));assertEquals("paid",migrated.path("construction").path("construction-201").path("laborMode").asText());
        ObjectNode again=migrate(migrated,json,0);assertEquals(migrated.path("accounts"),again.path("accounts"));
        assertEquals(migrated.path("agents"),again.path("agents"));assertEquals(migrated.path("loans"),again.path("loans"));
    }
    @Test void preservesExistingLoanBalancesAndUnspentLenderCashOnReapplication() throws Exception {
        ObjectMapper json=new ObjectMapper();State s=State.seed();State.Resident a=new State.Resident();a.id="owner";a.name="Owner";a.money=0;s.agents.put(a.id,a);
        Loans.finance(s,a,16000,5,"construction:house","saved",new Engine.Effects());s.loans.get("loan:saved").paidCents=50;
        ObjectNode saved=(ObjectNode)json.readTree(json.writeValueAsString(s)),migrated=migrate(saved,json,0);
        assertEquals(saved.path("loans"),migrated.path("loans"));assertEquals(saved.path("accounts"),migrated.path("accounts"));
    }
    private long sum(com.fasterxml.jackson.databind.JsonNode values) {long n=0;for(var value:values)n+=value.asLong();return n;}
    private ObjectNode migrate(ObjectNode saved,ObjectMapper json,int payments) throws Exception {
        Context context=mock(Context.class);Connection connection=mock(Connection.class);
        PreparedStatement query=mock(PreparedStatement.class),update=mock(PreparedStatement.class),insert=mock(PreparedStatement.class);ResultSet rows=mock(ResultSet.class);
        when(context.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT data::text FROM world_state WHERE id=1 FOR UPDATE")).thenReturn(query);
        when(connection.prepareStatement("UPDATE world_state SET data=?::jsonb WHERE id=1")).thenReturn(update);
        when(connection.prepareStatement("INSERT INTO transactions(world_minute,source,destination,amount,reason) VALUES(?,?,?,?,?)")).thenReturn(insert);
        when(query.executeQuery()).thenReturn(rows);when(rows.next()).thenReturn(true);when(rows.getString(1)).thenReturn(saved.toString());
        new V22__development_financing().migrate(context);var encoded=ArgumentCaptor.forClass(String.class);verify(update).setString(eq(1),encoded.capture());
        verify(insert,times(payments)).executeUpdate();return (ObjectNode)json.readTree(encoded.getValue());
    }
}
