package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import world.earth35.world.Loans;

/** Add explicit finance and labor defaults without inventing debts or rewriting residents. */
public class V22__development_financing extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        ObjectMapper json=new ObjectMapper();
        try(var query=context.getConnection().prepareStatement("SELECT data::text FROM world_state WHERE id=1 FOR UPDATE");var rows=query.executeQuery()) {
            if(!rows.next())return;
            ObjectNode data=(ObjectNode)json.readTree(rows.getString(1));
            if(!data.hasNonNull("loans"))data.set("loans",json.createObjectNode());
            if(data.path("construction").isObject())data.path("construction").forEach(site->{
                if(site instanceof ObjectNode object&&!object.hasNonNull("laborMode"))object.put("laborMode","paid");
            });
            ObjectNode accounts=data.withObject("/accounts");
            boolean capitalized=!accounts.has(Loans.LENDER_ACCOUNT);
            if(capitalized) {
                accounts.put(Loans.LENDER_ACCOUNT,Loans.LENDER_INITIAL_CAPITAL_CENTS);
                accounts.put("development-lender-capital-issuance",accounts.path("development-lender-capital-issuance").asLong()-Loans.LENDER_INITIAL_CAPITAL_CENTS);
            }
            data.put("version",data.path("version").asLong()+1);
            try(var update=context.getConnection().prepareStatement("UPDATE world_state SET data=?::jsonb WHERE id=1")) {
                update.setString(1,json.writeValueAsString(data));update.executeUpdate();
            }
            if(capitalized)try(var insert=context.getConnection().prepareStatement("INSERT INTO transactions(world_minute,source,destination,amount,reason) VALUES(?,?,?,?,?)")) {
                insert.setLong(1,data.path("minute").asLong());insert.setString(2,"development-lender-capital-issuance");
                insert.setString(3,Loans.LENDER_ACCOUNT);insert.setLong(4,Loans.LENDER_INITIAL_CAPITAL_CENTS);
                insert.setString(5,"development_lender_capital");insert.executeUpdate();
            }
        }
    }
}
