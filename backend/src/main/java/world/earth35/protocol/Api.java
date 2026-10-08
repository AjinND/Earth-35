package world.earth35.protocol;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import world.earth35.identity.Identity;
import world.earth35.memory.MemoryService;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.WorldStore;

@RestController
@RequestMapping("/v1")
public class Api {
    private final Identity identity;
    private final AgentService agents;
    private final WorldStore world;
    private final MemoryService memory;
    private final HostedAgentService hosted;
    public Api(Identity identity,AgentService agents,WorldStore world,MemoryService memory,HostedAgentService hosted) {this.identity=identity;this.agents=agents;this.world=world;this.memory=memory;this.hosted=hosted;}
    @PostMapping("/auth/register") public ResponseEntity<Map<String,Boolean>> register(@Valid @RequestBody Credentials input) {return loginResponse(identity.session(input,true));}
    @PostMapping("/auth/login") public ResponseEntity<Map<String,Boolean>> login(@Valid @RequestBody Credentials input) {return loginResponse(identity.session(input,false));}
    private ResponseEntity<Map<String,Boolean>> loginResponse(String token) { return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,ResponseCookie.from("e35_session",token).httpOnly(true).sameSite("Strict").path("/").maxAge(604800).build().toString()).body(Map.of("ok",true)); }
    @PostMapping("/auth/logout") public ResponseEntity<Map<String,Boolean>> logout(HttpServletRequest req) {identity.logout(req);return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,"e35_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0").body(Map.of("ok",true));}
    @GetMapping("/auth/me") public Map<String,String> me(HttpServletRequest req) {String owner=identity.owner(req);return Map.of("id",owner,"email",world.db.queryForObject("SELECT email FROM owners WHERE id=?",String.class,owner));}
    @GetMapping("/world") public World world() {return world.snapshot();}
    @GetMapping("/hosted/config") public Map<String,Object> hostedConfiguration() {return hosted.configuration();}
    @GetMapping("/events") public List<Event> events(@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="0") long before,@RequestParam(defaultValue="100") int limit,@RequestParam(defaultValue="") String agent) {
        if(before>0) return world.db.query("SELECT * FROM world_events WHERE id<? AND (?='' OR actor_id=? OR target_id=?) ORDER BY id DESC LIMIT ?",(rs,row)->new Event(rs.getLong("id"),rs.getLong("world_minute"),rs.getString("kind"),rs.getString("actor_id"),rs.getString("target_id"),rs.getString("message")),before,agent,agent,agent,Math.clamp(limit,1,100));
        return world.events(Math.max(0,after),Math.clamp(limit,1,100),agent);
    }
    @PostMapping("/agents") public AgentService.Registered create(HttpServletRequest req,@Valid @RequestBody Registration input) {String owner=identity.owner(req);return input.hosted()==null?agents.register(owner,input):hosted.register(owner,input);}
    @GetMapping("/owner/agents") public List<PublicAgent> mine(HttpServletRequest req) {return agents.mine(identity.owner(req));}
    @GetMapping("/owner/agents/{id}") public AgentService.Detail detail(HttpServletRequest req,@PathVariable String id) {return agents.detail(identity.owner(req),id);}
    @GetMapping("/owner/agents/{id}/runtime") public HostedAgentService.RuntimeStatus runtime(HttpServletRequest req,@PathVariable String id) {return hosted.status(identity.owner(req),id);}
    @PutMapping("/owner/agents/{id}/runtime") public Map<String,Boolean> configureRuntime(HttpServletRequest req,@PathVariable String id,@Valid @RequestBody HostedSetup input) {hosted.configure(identity.owner(req),id,input);return Map.of("ok",true);}
    @PutMapping("/owner/agents/{id}/pause") public Map<String,Boolean> pause(HttpServletRequest req,@PathVariable String id,@RequestBody Pause input) {agents.pause(identity.owner(req),id,input.paused());return Map.of("ok",true);}
    @PostMapping("/owner/agents/{id}/credentials") public Map<String,String> rotate(HttpServletRequest req,@PathVariable String id) {String owner=identity.owner(req);requireExternal(owner,id);return Map.of("token",agents.rotate(owner,id,false));}
    @DeleteMapping("/owner/agents/{id}/credentials") public Map<String,Boolean> revoke(HttpServletRequest req,@PathVariable String id) {String owner=identity.owner(req);requireExternal(owner,id);agents.rotate(owner,id,true);return Map.of("ok",true);}
    private void requireExternal(String owner,String id) {if(hosted.status(owner,id).mode().equals("hosted")) throw Identity.error(HttpStatus.CONFLICT,"Hosted agents do not use external bearer tokens");}
    @PostMapping("/owner/agents/{id}/memories/recall") public List<MemoryService.Memory> ownerMemories(HttpServletRequest req,@PathVariable String id,@Valid @RequestBody Recall input) {agents.owned(world.read(false),id,identity.owner(req));return memory.recall(id,input);}
    @GetMapping("/agent/observe") public AgentService.Observation observe(HttpServletRequest req,@RequestParam(defaultValue="0") long after) {return agents.observe(identity.agent(req),Math.max(0,after));}
    @PostMapping("/agent/action") public Receipt action(HttpServletRequest req,@Valid @RequestBody Command input) {return agents.action(identity.agent(req),input);}
    @PutMapping("/agent/routine") public Map<String,Boolean> routine(HttpServletRequest req,@Valid @RequestBody RoutineInput input) {agents.planRoutine(identity.agent(req),input);return Map.of("ok",true);}
    @PutMapping("/agent/night-plan") public Map<String,Boolean> nightPlan(HttpServletRequest req,@Valid @RequestBody NightPlanInput input) {agents.planNight(identity.agent(req),input);return Map.of("ok",true);}
    @PostMapping("/agent/night-routine") public NightRoutineResult nightRoutine(HttpServletRequest req,@Valid @RequestBody NightRoutineRequest input) {return new NightRoutineResult(agents.nightRoutine(identity.agent(req),input.requestId()));}
    @GetMapping("/agent/actions/{id}") public Receipt result(HttpServletRequest req,@PathVariable String id) {return agents.result(identity.agent(req),id);}
    @GetMapping("/agent/events") public List<Event> agentEvents(HttpServletRequest req,@RequestParam(defaultValue="0") long after) {return world.events(Math.max(0,after),100,identity.agent(req));}
    @PostMapping("/agent/memories") public Map<String,String> writeMemory(HttpServletRequest req,@Valid @RequestBody MemoryInput input) {return Map.of("id",memory.write(identity.agent(req),input));}
    @PostMapping("/agent/memories/recall") public List<MemoryService.Memory> recall(HttpServletRequest req,@Valid @RequestBody Recall input) {return memory.recall(identity.agent(req),input);}
    @GetMapping("/agent/conversation-summary") public Map<String,Object> pendingSummary(HttpServletRequest req) {
        var result=memory.pendingConversation(identity.agent(req));return Map.of("pending",result==null?List.of():List.of(result));
    }
    @PostMapping("/agent/conversation-summary/{episode}") public Map<String,String> summarize(HttpServletRequest req,@PathVariable long episode,@Valid @RequestBody MemoryService.SummaryInput input) {
        return Map.of("id",memory.summarizeConversation(identity.agent(req),episode,input));
    }
    @PostMapping("/agent/usage") public Map<String,Boolean> usage(HttpServletRequest req,@Valid @RequestBody Usage input) {agents.usage(identity.agent(req),input);return Map.of("ok",true);}
    @PutMapping("/agent/relationships") public Map<String,Boolean> relationship(HttpServletRequest req,@Valid @RequestBody Relationship input) {agents.relationship(identity.agent(req),input);return Map.of("ok",true);}
}
