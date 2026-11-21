package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.State.*;

class OutdoorPlantingTest {
    /** Fixture exported by the real TypeScript outdoorTrees/outdoorPatch generators from State.seed(). */
    @Test void physicalTreesAndRaisedBedsMatchTheRenderedSeedGeometry() throws Exception {
        State state=State.seed();
        try(var input=getClass().getResourceAsStream("/city/outdoor-planting-renderer-v1.json")) {
            assertNotNull(input);
            for(var expected:new ObjectMapper().readTree(input)) {
                String id=expected.path("place").asText();
                OutdoorPlanting.Layout actual=OutdoorPlanting.layout(state,state.buildings.get(id));
                assertEquals(expected.path("trees").size(),actual.trees().size(),id+" tree count");
                for(int i=0;i<actual.trees().size();i++) {
                    var tree=actual.trees().get(i);var front=expected.path("trees").get(i);
                    assertEquals(front.path("id").asInt(),tree.id(),id+" tree "+i);
                    assertEquals(front.path("kind").asText(),tree.kind(),id+" tree "+i);
                    assertEquals(front.path("x").asDouble(),tree.x(),1e-8,id+" tree x "+i);
                    assertEquals(front.path("z").asDouble(),tree.z(),1e-8,id+" tree z "+i);
                    assertEquals(front.path("size").asDouble(),tree.size(),1e-8,id+" tree scale "+i);
                    assertEquals(front.path("radius").asDouble(),tree.radius(),1e-8,id+" tree crown "+i);
                }
                assertEquals(expected.path("beds").size(),actual.beds().size(),id+" raised bed count");
                for(int i=0;i<actual.beds().size();i++) {
                    assertEquals(expected.path("beds").get(i).path("x").asDouble(),actual.beds().get(i).x(),1e-8,id+" bed x "+i);
                    assertEquals(expected.path("beds").get(i).path("z").asDouble(),actual.beds().get(i).z(),1e-8,id+" bed z "+i);
                }
                assertEquals(expected.path("benches").size(),actual.benches().size(),id+" bench count");
                for(int i=0;i<actual.benches().size();i++) {
                    var bench=actual.benches().get(i);var front=expected.path("benches").get(i);
                    assertEquals(front.path("x").asDouble(),bench.x(),1e-8,id+" bench x "+i);
                    assertEquals(front.path("z").asDouble(),bench.z(),1e-8,id+" bench z "+i);
                    assertEquals(front.path("rotation").asDouble(),bench.rotation(),1e-8,id+" bench heading "+i);
                }
            }
        }
    }
    @Test void clockChangesReusePlantingButNewOccupiedPropertiesRebuildIt() {
        State state=State.seed();Building place=state.buildings.get("meadow-commons");
        var first=OutdoorPlanting.layout(state,place);state.minute++;
        assertSame(first,OutdoorPlanting.layout(state,place));
        var tree=first.trees().getFirst();Building shed=new Building("new-shed","Shed","house","",tree.x(),tree.z(),1);
        state.buildings.put(shed.id,shed);var changed=OutdoorPlanting.layout(state,place);
        assertNotSame(first,changed);
        assertTrue(changed.trees().stream().noneMatch(t->t.id()==tree.id()),"Planting follows the same occupied-property clipping as the viewer");
    }
}
