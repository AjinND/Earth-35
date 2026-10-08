package world.earth35.world;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorldClockTest {
    @Test void waitsForTheDatabaseDeadlineWithoutReadingOrSavingWorldState() {
        JdbcTemplate db=mock(JdbcTemplate.class);
        WorldStore store=spy(new WorldStore(db,null));
        WorldClock clock=new WorldClock(store,5000);
        when(db.update(anyString(),eq(5000L))).thenReturn(0);

        clock.tick();

        verify(db).update(contains("last_tick_at <= clock_timestamp()"),eq(5000L));
        verify(store,never()).read(anyBoolean());
        verify(store,never()).effects(any(),any());
        verify(store,never()).save(any());
    }

    @Test void advancesOnlyAfterClaimingTheDurableDeadline() {
        JdbcTemplate db=mock(JdbcTemplate.class);
        WorldStore store=spy(new WorldStore(db,null));
        State state=State.seed();
        long minute=state.minute;
        when(db.update(anyString(),eq(100L))).thenReturn(1);
        doReturn(state).when(store).read(true);
        doNothing().when(store).effects(any(),any());
        doNothing().when(store).save(any());
        WorldClock clock=new WorldClock(store,100);

        clock.tick();

        var order=inOrder(db,store);
        order.verify(db).update(contains("last_tick_at=clock_timestamp()"),eq(100L));
        order.verify(store).read(true);
        ArgumentCaptor<Engine.Effects> effects=ArgumentCaptor.forClass(Engine.Effects.class);
        order.verify(store).effects(same(state),effects.capture());
        order.verify(store).save(same(state));
        assertEquals(minute+1,state.minute);
        assertNotNull(effects.getValue());
    }

    @Test void repeatedChecksAdvanceOnlyTheTickClaimedByTheDatabase() {
        JdbcTemplate db=mock(JdbcTemplate.class);
        WorldStore store=spy(new WorldStore(db,null));
        State state=State.seed();
        long minute=state.minute;
        when(db.update(anyString(),eq(5000L))).thenReturn(0,1,0);
        doReturn(state).when(store).read(true);
        doNothing().when(store).effects(any(),any());
        doNothing().when(store).save(any());
        WorldClock clock=new WorldClock(store,5000);

        clock.tick();
        clock.tick();
        clock.tick();

        verify(db,times(3)).update(contains("last_tick_at <= clock_timestamp()"),eq(5000L));
        verify(store).read(true);
        verify(store).effects(same(state),any());
        verify(store).save(same(state));
        assertEquals(minute+1,state.minute);
    }
}
