package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Introduce empty resident-owned objects without changing existing geography or activity. */
public class V23__resident_world_objects extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try(var update=context.getConnection().prepareStatement("UPDATE world_state SET data=jsonb_set(data,'{worldObjects}','{}'::jsonb) WHERE id=1 AND (NOT jsonb_exists(data,'worldObjects') OR data->'worldObjects'='null'::jsonb)")) {
            update.executeUpdate();
        }
    }
}
