package world.earth35.world;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WorldClock {
    private final WorldStore store;
    private final long tickMs;
    public WorldClock(WorldStore store,@Value("${earth35.tick-ms}") long tickMs) { this.store=store;this.tickMs=tickMs; }
    // Check the durable deadline frequently: waiting a full tick after the last
    // transaction finishes adds its execution time to every simulated minute.
    @Scheduled(fixedDelay=100)
    @Transactional
    public void tick() {
        // Database gate prevents multiple application replicas advancing the same clock twice.
        int due=store.db.update("UPDATE world_state SET last_tick_at=clock_timestamp() WHERE id=1 AND last_tick_at <= clock_timestamp() - (? * interval '1 millisecond')",tickMs);
        if(due==0) return;
        State s=store.read(true);
        Engine.Effects effects=Engine.tick(s); store.effects(s,effects); store.save(s);
    }
}
