package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import world.earth35.world.Library;

/** Update the library's purpose without changing the checksummed original place catalog. */
public class V19__library_visit_purpose extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try(var update=context.getConnection().prepareStatement("UPDATE world_state SET data=jsonb_set(data,'{buildings,library,description}',to_jsonb(?::text),false) WHERE id=1 AND jsonb_exists(data->'buildings','library')")) {
            update.setString(1,Library.PURPOSE);update.executeUpdate();
        }
    }
}
