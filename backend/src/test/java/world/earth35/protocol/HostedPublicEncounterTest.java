package world.earth35.protocol;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.*;
import world.earth35.world.State.*;

class HostedPublicEncounterTest {
    private AgentService.Observation observation(State s,Resident a) {
        return new AgentService.Observation(s.minute,1,"Clear","12:00",false,1800,a,List.of(),List.of(),List.of(),
            List.of(),List.of(),List.of(),List.of(),null,List.of(),List.of(),null,null,Social.situation(s,a,Map.of()),null);
    }
    private PublicCall call(State s,Resident a) {
        PublicCall call=new PublicCall();call.id="call";call.caller="caller";call.listener=a.id;call.message="Hey!";
        call.minute=s.minute;call.expires=s.minute+10;s.publicCalls.put(call.id,call);return call;
    }
    @Test void heardCallsWakeRestingAndWaitingResidentsWithoutInterruptingBusyActivities() {
        State s=State.prototypeSeed();Resident a=new Resident();a.id="listener";s.agents.put(a.id,a);call(s,a);
        for(String activity:List.of("REST","WAIT_RAIN","WAIT_BUS","MOVE","RUN")) {
            a.pending=new Pending();a.pending.id="unchanged";a.pending.type=activity;
            assertTrue(HostedAgentService.decisionDue(observation(s,a)),activity);
            assertEquals("unchanged",a.pending.id);assertNull(a.pending.socialWith);
        }
        for(String activity:List.of("WORK","SLEEP","READ_BOOK","BOARD_BUS","RIDE_BUS")) {
            a.pending.type=activity;assertFalse(HostedAgentService.decisionDue(observation(s,a)),activity);
        }
    }
    @Test void answeredExpiredAndOtherResidentsCallsNeverWakeARestingListener() {
        State s=State.prototypeSeed();Resident a=new Resident();a.id="listener";s.agents.put(a.id,a);
        a.pending=new Pending();a.pending.type="REST";PublicCall call=call(s,a);
        for(String status:List.of("acknowledged","declined","ignored","expired")) {
            call.status=status;assertFalse(HostedAgentService.decisionDue(observation(s,a)),status);
        }
        call.status="pending";call.expires=s.minute;assertFalse(HostedAgentService.decisionDue(observation(s,a)));
        call.expires=s.minute+10;call.listener="someone-else";assertFalse(HostedAgentService.decisionDue(observation(s,a)));
        call.listener=a.id;a.paused=true;assertFalse(HostedAgentService.decisionDue(observation(s,a)));
    }
}
