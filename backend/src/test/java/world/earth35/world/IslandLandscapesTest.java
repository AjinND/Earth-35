package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.State.*;
import world.earth35.protocol.Contracts.*;

class IslandLandscapesTest {
    private State previousWorld() {State s=State.prototypeSeed();OrganicCity.upgrade(s);CityExpansion.upgrade(s);return s;}
    private Resident walker(State s,double x,double z) {
        Resident a=new Resident();a.id="explorer";a.ownerId="owner";a.name="Explorer";a.home="apt-101";a.location="point:"+x+","+z;a.x=x;a.z=z;a.money=12345;a.energy=100;
        a.relationships.put("friend","close friend");s.agents.put(a.id,a);return a;
    }
    @Test void migrationOnlyAddsViewpointsAndVersionAndPreservesUnknownPrivateData() throws Exception {
        State s=previousWorld();Resident a=walker(s,-225,-450);a.pending=Engine.pending("saved-rest","REST",a.location,s.minute+20);
        ObjectMapper json=new ObjectMapper();ObjectNode data=json.valueToTree(s);data.put("futureField","keep");
        ((ObjectNode)data.at("/agents/explorer")).put("privateNotes","keep");
        ((ObjectNode)data.at("/agents/explorer/pending")).put("futureAction","keep");
        ObjectNode before=data.deepCopy();IslandLandscapes.upgrade(data);
        assertEquals(3,data.path("geographyVersion").asInt());assertEquals(s.version+1,data.path("version").asLong());
        for(String field:List.of("agents","minute","jobs","accounts","worldObjects","futureField"))assertEquals(before.path(field),data.path(field),field);
        before.path("buildings").fields().forEachRemaining(field->assertEquals(field.getValue(),data.path("buildings").path(field.getKey())));
        assertEquals(170,data.path("buildings").size());ObjectNode once=data.deepCopy();IslandLandscapes.upgrade(data);assertEquals(once,data);
    }
    @Test void lakeAndCliffEdgesAreBlockedButTheCompleteMarkedTrailIsDry() {
        var lake=IslandLandscapes.SPEC.lake();assertFalse(IslandGeography.dryLand(lake.x(),lake.z()));
        assertNull(IslandGeography.coordinate("point:-132,-398"));
        for(var viewpoint:IslandLandscapes.SPEC.viewpoints())assertNotNull(IslandGeography.coordinate("point:"+viewpoint.x()+","+viewpoint.z()));
        for(var trail:List.of(IslandLandscapes.SPEC.trail(),IslandLandscapes.SPEC.fallsTrail()))for(int segment=1;segment<trail.size();segment++) {
            var a=trail.get(segment-1);var b=trail.get(segment);int steps=(int)Math.ceil(Math.hypot(b.get(0)-a.get(0),b.get(1)-a.get(1))*2);
            for(int i=0;i<=steps;i++)assertTrue(IslandGeography.dryLand(a.get(0)+(b.get(0)-a.get(0))*i/steps,a.get(1)+(b.get(1)-a.get(1))*i/steps),"Trail segment "+segment);
        }
        assertFalse(IslandLandscapes.clear(-94,-365));
        assertTrue(IslandGeography.dryLand(-95, -322));
    }
    @Test void unsafeOccupiedSitesOrAcceptedDestinationsAbortWithoutChangingSavedState() {
        ObjectMapper json=new ObjectMapper();State s=previousWorld();Resident a=walker(s,-132,-398);
        var before=json.valueToTree(s);assertThrows(IllegalStateException.class,()->IslandLandscapes.upgrade(s));assertEquals(before,json.valueToTree(s));
        a.x=-172;a.z=-399;a.location="point:-172,-399";a.pending=Engine.pending("accepted","MOVE","point:-132,-398",s.minute+50);
        before=json.valueToTree(s);assertThrows(IllegalStateException.class,()->IslandLandscapes.upgrade(s));assertEquals(before,json.valueToTree(s));
        a.pending=null;Building occupied=new Building("saved-cabin","Saved cabin","house","Highlands",-132,-398,1);s.buildings.put(occupied.id,occupied);
        before=json.valueToTree(s);assertThrows(IllegalStateException.class,()->IslandLandscapes.upgrade(s));assertEquals(before,json.valueToTree(s));
    }
    @Test void affectedAcceptedWalkKeepsItsReceiptAndFinishesAroundTheLake() throws Exception {
        State s=previousWorld();Resident a=walker(s,-166,-400);
        a.pending=Engine.pending("accepted-receipt","MOVE","point:-145,-377",s.minute+100);a.pending.route=new ArrayList<>(List.of(new double[]{-145,-377}));a.pending.frontDoorRoute=true;
        ObjectMapper json=new ObjectMapper();ObjectNode data=json.valueToTree(s);((ObjectNode)data.at("/agents/explorer/pending")).put("futureAction","keep");
        IslandLandscapes.upgrade(data);assertEquals("keep",data.at("/agents/explorer/pending/futureAction").asText());
        s=json.readerFor(State.class).without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(data);a=s.agents.get("explorer");
        assertEquals("accepted-receipt",a.pending.id);assertEquals(12345,a.money);assertTrue(a.pending.route.size()>1);
        for(int minute=0;minute<300&&a.pending!=null;minute++){Engine.tick(s);assertTrue(IslandGeography.dryLand(a.x,a.z),"Walker crossed water or a cliff");}
        assertNull(a.pending);assertEquals("point:-145,-377",a.location);
    }
    @Test void residentsCanChooseBothViewpointsRestAndReturnHomeAcrossTheFootbridge() {
        State s=State.seed();Resident a=walker(s,-225,-450);
        for(String target:List.of("cloudmere-view","silverstream-falls",a.home)) {
            Engine.apply(s,a,new Command(UUID.randomUUID().toString(),ActionType.MOVE,target,null,null,null,null),"chosen-outing",new Engine.Effects());
            for(int minute=0;minute<500&&a.pending!=null;minute++){Engine.tick(s);assertTrue(IslandGeography.dryLand(a.x,a.z));}
            assertNull(a.pending,"Journey did not finish: "+target);assertEquals(target,a.location);
            Engine.apply(s,a,new Command(UUID.randomUUID().toString(),ActionType.REST,null,null,null,null,null),"chosen-rest",new Engine.Effects());
            while(a.pending!=null)Engine.tick(s);
        }
    }
    @Test void exportAuthoritativeWaterAndCliffSurveyForFrontendParity() throws Exception {
        List<Map<String,Object>> survey=new ArrayList<>();
        for(int x=-184;x<=-60;x+=2)for(int z=-434;z<=-316;z+=2)survey.add(Map.of("x",x,"z",z,"dry",IslandGeography.dryLand(x,z)));
        Files.writeString(Path.of("target/landscape-survey.json"),new ObjectMapper().writeValueAsString(survey));
    }
}
