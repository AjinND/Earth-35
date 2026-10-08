package world.earth35.protocol;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import world.earth35.world.WorldStore;

@Configuration
@EnableWebSocket
public class WorldSocket extends TextWebSocketHandler implements WebSocketConfigurer {
    private final WorldStore store;
    private final String origin;
    private final ConcurrentHashMap<String,WebSocketSession> sessions=new ConcurrentHashMap<>();
    public WorldSocket(WorldStore store,@Value("${earth35.web-origin}") String origin) {this.store=store;this.origin=origin;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {registry.addHandler(this,"/ws/world").setAllowedOrigins(origin);}
    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if(sessions.size()>=200) {session.close(CloseStatus.SERVICE_OVERLOAD);return;}
        var safe=new ConcurrentWebSocketSessionDecorator(session,3000,256000);sessions.put(session.getId(),safe);
        safe.sendMessage(new TextMessage(store.encode(store.snapshot())));
    }
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status) {sessions.remove(session.getId());}
    @Scheduled(fixedDelay=1000) public void broadcast() {
        if(sessions.isEmpty()) return;
        var snapshot=store.snapshot();String message=store.encode(snapshot);
        for(var session:sessions.values()) try {session.sendMessage(new TextMessage(message));} catch(Exception e) {sessions.remove(session.getId());try {session.close();} catch(Exception ignored) {}}
        // Events are committed before publication; reconnects use durable snapshots/cursors.
        long max=snapshot.events().stream().mapToLong(Contracts.Event::id).max().orElse(0);
        store.db.update("UPDATE world_events SET published=true WHERE NOT published AND id<=?",max);
    }
}
