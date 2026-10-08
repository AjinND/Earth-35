package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.State.*;
import world.earth35.protocol.Contracts.*;

class ConnectedLandscapesTest {
    private State highlandWorld() {
        State s=State.prototypeSeed();OrganicCity.upgrade(s);CityExpansion.upgrade(s);IslandLandscapes.upgrade(s);return s;
    }
    private Resident walker(State s,double x,double z) {
        Resident a=new Resident();a.id="explorer";a.ownerId="owner";a.name="Explorer";a.home="apt-101";
        a.location="point:"+x+","+z;a.x=x;a.z=z;a.money=12345;a.energy=100;a.relationships.put("friend","close friend");s.agents.put(a.id,a);return a;
    }
    @Test void historicalV24StillAddsOnlyItsTwoPlacesAndV25PreservesAllOtherJson() throws Exception {
        State s=highlandWorld();assertEquals(3,s.geographyVersion);assertEquals(170,s.buildings.size());
        assertFalse(s.buildings.containsKey("moonwash-cove"));
        Resident a=walker(s,-595,-100);a.pending=Engine.pending("saved-rest","REST",a.location,s.minute+20);
        ObjectMapper json=new ObjectMapper();ObjectNode data=json.valueToTree(s);data.put("futureWorldField","keep");
        ((ObjectNode)data.at("/agents/explorer")).put("privateNotes","keep");
        ((ObjectNode)data.at("/agents/explorer/pending")).put("futureAction","keep");
        ((ObjectNode)data.at("/agents/explorer/pending")).remove("route");
        ((ObjectNode)data.at("/buildings/apt-101")).put("futurePropertyMetadata","keep");
        ObjectNode before=data.deepCopy();IslandLandscapes.completePhase3(data);
        assertEquals(4,data.path("geographyVersion").asInt());assertEquals(s.version+1,data.path("version").asLong());
        for(String field:List.of("agents","minute","jobs","accounts","worldObjects","futureWorldField"))assertEquals(before.path(field),data.path(field),field);
        before.path("buildings").fields().forEachRemaining(field->assertEquals(field.getValue(),data.path("buildings").path(field.getKey())));
        assertEquals(174,data.path("buildings").size());ObjectNode once=data.deepCopy();IslandLandscapes.completePhase3(data);assertEquals(once,data);
    }
    @Test void exposedCliffSitesAndAcceptedDestinationsAbortAtomically() {
        ObjectMapper json=new ObjectMapper();State s=highlandWorld();Resident a=walker(s,-729,38);
        var before=json.valueToTree(s);assertThrows(IllegalStateException.class,()->IslandLandscapes.completePhase3(s));assertEquals(before,json.valueToTree(s));
        a.x=-680;a.z=10;a.location="point:-680,10";a.pending=Engine.pending("accepted","MOVE","point:-729,38",s.minute+50);
        before=json.valueToTree(s);assertThrows(IllegalStateException.class,()->IslandLandscapes.completePhase3(s));assertEquals(before,json.valueToTree(s));
        a.pending=null;Building cabin=new Building("saved-cabin","Saved cabin","house","Western Coast",-711,38,1);s.buildings.put(cabin.id,cabin);
        before=json.valueToTree(s);assertThrows(IllegalStateException.class,()->IslandLandscapes.completePhase3(s));assertEquals(before,json.valueToTree(s));
        s.buildings.remove(cabin.id);WorldObjects.PlacedObject sign=new WorldObjects.PlacedObject();sign.id="private-sign";sign.x=-711.5;sign.z=38;s.worldObjects.put(sign.id,sign);
        before=json.valueToTree(s);assertThrows(IllegalStateException.class,()->IslandLandscapes.completePhase3(s));assertEquals(before,json.valueToTree(s));
        s.worldObjects.clear();a.x=-707;a.z=38;a.location="point:-707,38";a.pending=Engine.pending("reserved-install","PLACE_OBJECT",null,s.minute+15);
        a.pending.objectX=-711.5;a.pending.objectZ=38;
        before=json.valueToTree(s);assertThrows(IllegalStateException.class,()->IslandLandscapes.completePhase3(s));assertEquals(before,json.valueToTree(s));
    }
    @Test void savedWalkReroutesAroundCoastalCliffWithoutReplacingReceiptOrPrivateFields() throws Exception {
        State s=highlandWorld();Resident a=walker(s,-700,70);
        a.pending=Engine.pending("accepted-receipt","MOVE","point:-730,-20",s.minute+100);a.pending.route=new ArrayList<>(List.of(new double[]{-730,-20}));
        a.pending.frontDoorRoute=true;a.pending.routeChoice="shortest";
        ObjectMapper json=new ObjectMapper();ObjectNode data=json.valueToTree(s);((ObjectNode)data.at("/agents/explorer/pending")).put("futureAction","keep");
        IslandLandscapes.completePhase3(data);assertEquals("keep",data.at("/agents/explorer/pending/futureAction").asText());
        s=json.readerFor(State.class).without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(data);a=s.agents.get("explorer");
        assertEquals("accepted-receipt",a.pending.id);assertEquals(12345,a.money);assertTrue(a.pending.route.size()>1);
        finish(s,a,"point:-730,-20");
    }
    @Test void trailNetworkAndEveryBridgeHaveSafeCompleteTraversalsAndDryArrivals() {
        for(var trail:IslandLandscapes.allTrails())for(int segment=1;segment<trail.size();segment++) {
            var a=trail.get(segment-1);var b=trail.get(segment);int steps=Math.max(1,(int)Math.ceil(Math.hypot(b.get(0)-a.get(0),b.get(1)-a.get(1))*2));
            for(int i=0;i<=steps;i++)assertTrue(IslandGeography.dryLand(a.get(0)+(b.get(0)-a.get(0))*i/steps,a.get(1)+(b.get(1)-a.get(1))*i/steps),"Trail segment "+segment+" "+a+" -> "+b);
        }
        for(var bridge:IslandLandscapes.SPEC.bridges()) {
            for(double across:new double[]{-bridge.width()/2+.02,0,bridge.width()/2-.02})for(double x=bridge.x()-bridge.length()/2-2;x<=bridge.x()+bridge.length()/2+2;x+=.25)
                assertTrue(IslandGeography.dryLand(x,bridge.z()+across),"Bridge deck/abutment is disconnected: "+bridge.id());
            assertFalse(IslandGeography.dryLand(bridge.x(),bridge.z()+bridge.width()/2+.6),"No invisible crossing beside bridge: "+bridge.id());
        }
        for(var viewpoint:IslandLandscapes.SPEC.viewpoints())assertNotNull(IslandGeography.coordinate("point:"+viewpoint.x()+","+viewpoint.z()));
        assertFalse(IslandGeography.dryLand(-729,38));assertNull(IslandGeography.coordinate("point:-729,38"));
    }
    @Test void residentsCanChooseEachOutdoorDestinationRestAndReturnHome() {
        State s=State.seed();Resident a=walker(s,-225,-450);
        assertEquals(4,CityLayout.of(s).version());assertEquals(174,s.buildings.size());
        List<String> destinations=new ArrayList<>(IslandLandscapes.SPEC.viewpoints().stream().map(IslandLandscapes.Viewpoint::id).toList());destinations.add(a.home);
        for(String target:destinations) {
            Building b=s.buildings.get(target);if(!target.equals(a.home)){assertTrue(b.social);assertTrue(b.restAllowed);assertFalse(Places.enclosed(b));assertTrue(Places.open(b,s.minute));}
            Engine.apply(s,a,new Command(UUID.randomUUID().toString(),ActionType.MOVE,target,null,null,null,null),"chosen-outing",new Engine.Effects());finish(s,a,target);
            Engine.apply(s,a,new Command(UUID.randomUUID().toString(),ActionType.REST,null,null,null,null,null),"chosen-rest",new Engine.Effects());while(a.pending!=null)Engine.tick(s);
        }
    }
    @Test void anActualResidentUsesTheValleyDeckInsteadOfAnInvisibleParallelCrossing() {
        State s=State.seed();var bridge=IslandLandscapes.SPEC.bridges().stream().filter(b->b.id().equals("valley-footbridge")).findFirst().orElseThrow();
        Resident a=walker(s,bridge.x()-11,bridge.z()+bridge.width()/2+.6);
        String target="point:"+(bridge.x()+11)+","+a.z;
        Engine.apply(s,a,new Command("chosen-crossing",ActionType.MOVE,target,null,null,null,null),"accepted-crossing",new Engine.Effects());
        assertTrue(a.pending.route.size()>1,"The parallel route must turn onto the actual bridge");
        boolean crossedDeck=false;
        for(int minute=0;minute<100&&a.pending!=null;minute++) {
            Engine.tick(s);assertTrue(IslandGeography.dryLand(a.x,a.z));
            if(IslandLandscapes.bridgeAt(a.x,a.z)==bridge)crossedDeck=true;
        }
        assertTrue(crossedDeck,"Resident never reached the valley footbridge deck");assertNull(a.pending);assertEquals(target,a.location);
    }
    @Test void installingASignCannotBlockTheMarkedRidgeTrailOrChargeForARejectedSite() {
        State s=State.seed();double x=-332.5,z=-437.5,nx=35/Math.hypot(65,35),nz=65/Math.hypot(65,35);
        Resident a=walker(s,x+nx*3,z+nz*3);a.money=100000;
        long money=a.money;Map<String,Long> accounts=new LinkedHashMap<>(s.accounts);
        Engine.Invalid rejected=assertThrows(Engine.Invalid.class,()->WorldObjects.place(s,a,"point:"+x+","+z,"signboard","Ridge trail","blocked-sign",new Engine.Effects()));
        assertTrue(rejected.getMessage().contains("trail"));assertEquals(money,a.money);assertEquals(accounts,s.accounts);assertNull(a.pending);assertTrue(s.worldObjects.isEmpty());
        double clearX=x+nx*5,clearZ=z+nz*5;a.x=clearX+nx*3;a.z=clearZ+nz*3;a.location="road";
        WorldObjects.place(s,a,"point:"+clearX+","+clearZ,"signboard","Beside the ridge trail","clear-sign",new Engine.Effects());
        for(int minute=0;minute<15;minute++)Engine.tick(s);
        assertNull(a.pending);assertNotNull(s.worldObjects.get("object:clear-sign"));assertEquals(money-WorldObjects.COST_CENTS,a.money);
    }
    @Test void newConstructionCannotBlockAMarkedTrailButNearbyWildernessRemainsBuildable() {
        State s=State.seed();double x=-332.5,z=-437.5,nx=35/Math.hypot(65,35),nz=65/Math.hypot(65,35);
        Resident a=walker(s,x-nx*15,z-nz*15);a.money=100000;
        long money=a.money;Map<String,Long> accounts=new LinkedHashMap<>(s.accounts);int places=s.buildings.size(),sites=s.construction.size();
        Engine.Invalid rejected=assertThrows(Engine.Invalid.class,()->Construction.start(s,a,"point:"+x+","+z,"bakery","Blocked trail",24000L,"blocked-building",new Engine.Effects()));
        assertTrue(rejected.getMessage().contains("trail"));assertEquals(money,a.money);assertEquals(accounts,s.accounts);assertEquals(places,s.buildings.size());assertEquals(sites,s.construction.size());
        double clearX=x+nx*12,clearZ=z+nz*12;
        Construction.start(s,a,"point:"+clearX+","+clearZ,"bakery","Ridge bakery",24000L,"clear-building",new Engine.Effects());
        assertNotNull(s.buildings.get("site:clear-building"));assertEquals(money-24000,a.money);assertNull(a.pending);
    }
    @Test void migrationRejectsSavedTrailObstructionsWithoutChangingUnknownJson() throws Exception {
        ObjectMapper json=new ObjectMapper();State s=highlandWorld();
        WorldObjects.PlacedObject sign=new WorldObjects.PlacedObject();sign.id="saved-sign";sign.x=-332.5;sign.z=-437.5;s.worldObjects.put(sign.id,sign);
        ObjectNode data=json.valueToTree(s);data.put("privateFutureMetadata","keep");ObjectNode before=data.deepCopy();
        assertThrows(IllegalStateException.class,()->IslandLandscapes.completePhase3(data));assertEquals(before,data);
        s.worldObjects.clear();Building cabin=new Building("saved-cabin","Saved cabin","house","Western Ridge",-329.5,-433.5,1);s.buildings.put(cabin.id,cabin);
        ObjectNode saved=json.valueToTree(s);saved.put("privateFutureMetadata","keep");ObjectNode savedBefore=saved.deepCopy();
        assertThrows(IllegalStateException.class,()->IslandLandscapes.completePhase3(saved));assertEquals(savedBefore,saved);
    }
    private void finish(State s,Resident a,String target) {
        for(int minute=0;minute<600&&a.pending!=null;minute++){Engine.tick(s);assertTrue(IslandGeography.dryLand(a.x,a.z),"Walker crossed water or a cliff");}
        assertNull(a.pending,"Journey did not finish: "+target);assertEquals(target,a.location);
    }
    @Test void exportAuthoritativeConnectedTerrainSurvey() throws Exception {
        List<Map<String,Object>> survey=new ArrayList<>();
        for(int x=-184;x<=-60;x+=2)for(int z=-434;z<=-316;z+=2)add(survey,x,z);
        for(int x=-758;x<=-660;x+=2)for(int z=-196;z<=96;z+=2)add(survey,x,z);
        for(int x=150;x<=240;x+=3)for(int z=85;z<=190;z+=3)add(survey,x,z);
        for(var cliff:IslandLandscapes.SPEC.protectedCliffs())for(int i=0;i<128;i++)for(double radius:new double[]{.999,1.001}) {
            double angle=i*Math.PI*2/128;add(survey,cliff.x()+Math.cos(angle)*cliff.radiusX()*radius,cliff.z()+Math.sin(angle)*cliff.radiusZ()*radius);
        }
        for(var bridge:IslandLandscapes.SPEC.bridges())for(double x=bridge.x()-bridge.length()/2-4;x<=bridge.x()+bridge.length()/2+4;x+=.5)
            for(double offset:new double[]{-bridge.width()/2-.02,-bridge.width()/2+.02,0,bridge.width()/2-.02,bridge.width()/2+.02})add(survey,x,bridge.z()+offset);
        for(var trail:IslandLandscapes.allTrails())for(int segment=1;segment<trail.size();segment++) {
            var a=trail.get(segment-1);var b=trail.get(segment);int steps=Math.max(1,(int)Math.ceil(Math.hypot(b.get(0)-a.get(0),b.get(1)-a.get(1))*2));
            for(int i=0;i<=steps;i++)add(survey,a.get(0)+(b.get(0)-a.get(0))*i/steps,a.get(1)+(b.get(1)-a.get(1))*i/steps);
        }
        for(var viewpoint:IslandLandscapes.SPEC.viewpoints())add(survey,viewpoint.x(),viewpoint.z());
        Files.writeString(Path.of("target/phase3-landscape-survey.json"),new ObjectMapper().writeValueAsString(survey));
    }
    private void add(List<Map<String,Object>> survey,double x,double z) {survey.add(Map.of("x",x,"z",z,"dry",IslandGeography.dryLand(x,z)));}
}
