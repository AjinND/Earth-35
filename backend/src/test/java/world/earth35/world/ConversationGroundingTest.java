package world.earth35.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class ConversationGroundingTest {
    private Resident resident(State s,String id,String name,String location) {
        Resident a=new Resident();a.id=id;a.name=name;a.home="apt-101";a.location=location;
        Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(id,a);return a;
    }
    private State world() {
        State s=State.prototypeSeed();s.minute=1179;
        resident(s,"oliver","Oliver Reed","community-garden");resident(s,"theo","Theo Brooks","community-garden");
        resident(s,"aisha","Aisha Patel","park");resident(s,"diego","Diego Morales","courtyard");
        return s;
    }
    private Engine.Effects speak(State s,ActionType type,String message) {
        Engine.Effects effects=new Engine.Effects();
        Engine.apply(s,s.agents.get("oliver"),new Command(UUID.randomUUID().toString(),type,"theo",message,null,null,null),"turn",effects);
        return effects;
    }
    @Test void absentCompanionsCannotBecomePresentThroughAnActivityLabelOrSpeech() {
        State s=world();Resident oliver=s.agents.get("oliver");
        String message="Hi Theo! I'm Oliver. I'm here at the community garden with Aisha and Diego, discussing interest in starting one. Would you like to join our conversation?";
        Engine.Effects effects=new Engine.Effects();
        Engine.Invalid rejected=assertThrows(Engine.Invalid.class,()->Engine.apply(s,oliver,
            new Command("invented-company",ActionType.TALK,"theo",message,null,null,null),"turn",effects));
        assertTrue(rejected.getMessage().contains("not physically present"));
        assertTrue(s.conversations.isEmpty());assertTrue(oliver.encounterReviewed.isEmpty());
        assertTrue(effects.facts.isEmpty());assertTrue(effects.completions.isEmpty());assertNull(oliver.pending);
        assertFalse(Conversations.active(s,oliver));
    }
    @Test void namedSubjectsAndCurrentJointActionsAreCheckedWithUniqueFirstNames() {
        for(String message:List.of("Aisha and Diego are here.","Hi Theo, Aisha and Diego are here with me.",
            "I am discussing the garden with Aisha and Diego.","We are working with Diego Morales.")) {
            State s=world();assertThrows(Engine.Invalid.class,()->speak(s,ActionType.TALK,message),message);
            assertTrue(s.conversations.isEmpty());
        }
    }
    @Test void invitationsPlansQuestionsAndPastReportsRemainAuthoredSpeech() {
        for(String message:List.of("I hope to meet with Aisha and Diego.","I'm here hoping to talk with Aisha.",
            "I discussed the garden with Aisha and Diego earlier.","I'm planning to meet with Aisha and Diego.",
            "Aisha said, 'I'm here with Diego.'", "Are Aisha and Diego here?", "Aisha and Diego are here?",
            "I don't think Aisha and Diego are here.", "I'm not sure Aisha is here.", "I doubt Aisha is here.",
            "If Aisha and Diego are here, shall we invite them?", "I'm working with Aisha tomorrow.",
            "I'm here after discussing the plan with Aisha.","We agreed to meet with Aisha, but she has not arrived.")) {
            State s=world();assertDoesNotThrow(()->speak(s,ActionType.TALK,message),message);
            assertEquals(message,s.conversations.values().iterator().next().turns.getLast().message());
        }
    }
    @Test void uncertaintyDoesNotExemptALaterAffirmativeClaimOrUnrelatedNegative() {
        for(String message:List.of("I doubt it, but Aisha is here.","I'm not sure about the garden, but I'm here with Aisha.",
            "I don't like rain. Aisha and Diego are here.")) {
            State s=world();assertThrows(Engine.Invalid.class,()->speak(s,ActionType.TALK,message),message);
            assertTrue(s.conversations.isEmpty());
        }
    }
    @Test void actualCompanyIsAllowedButMustStillBeVisibleAndNearWhenSpeaking() {
        State s=world();Resident oliver=s.agents.get("oliver"),aisha=s.agents.get("aisha"),diego=s.agents.get("diego");
        aisha.location=diego.location=oliver.location;aisha.x=diego.x=oliver.x;aisha.z=diego.z=oliver.z;
        assertEquals(1,speak(s,ActionType.TALK,"I'm here with Aisha and Diego.").facts.size());
        Conversations.end(s,oliver);diego.x+=11;
        assertThrows(Engine.Invalid.class,()->speak(s,ActionType.TALK,"Aisha and Diego are here."));
        diego.x=oliver.x;diego.location="apt-101";
        assertThrows(Engine.Invalid.class,()->speak(s,ActionType.TALK,"I'm here with Diego."));
    }
    @Test void rejectedFarewellCannotRecordInventedAttendanceOrCloseTheActualChat() {
        State s=world();speak(s,ActionType.TALK,"Hello Theo!");
        Conversation chat=s.conversations.get(Conversations.key("oliver","theo"));
        assertThrows(Engine.Invalid.class,()->speak(s,ActionType.END_CHAT,"I'm here with Aisha. Take care!"));
        assertFalse(chat.closed);assertEquals(1,chat.turns.size());
        speak(s,ActionType.END_CHAT,"I'll invite Aisha another time. See you, Theo!");
        assertTrue(chat.closed);assertEquals(2,chat.turns.size());
    }
    @Test void ambiguousShortNamesRequireAnExplicitIdentityBeforeCheckingAttendance() {
        State s=world();resident(s,"aisha-2","Aisha Khan","community-garden");
        Engine.Invalid ambiguous=assertThrows(Engine.Invalid.class,()->speak(s,ActionType.TALK,"I'm here with Aisha."));
        assertTrue(ambiguous.getMessage().contains("ambiguous"));assertTrue(s.conversations.isEmpty());
        assertDoesNotThrow(()->speak(s,ActionType.TALK,"I'm here with Aisha Khan."));
        Conversations.end(s,s.agents.get("oliver"));
        assertThrows(Engine.Invalid.class,()->speak(s,ActionType.TALK,"I'm here with Aisha Patel."));
    }
    @Test void rejectedPublicCallCannotCreateAnEventOrChangeEncounterReview() {
        State s=world();Resident oliver=s.agents.get("oliver");Engine.Effects effects=new Engine.Effects();
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,oliver,
            new Command("call",ActionType.CALL_OUT,"theo","I'm here with Aisha and Diego. Join us?",null,null,null),"call",effects));
        assertTrue(s.publicCalls.isEmpty());assertTrue(s.conversations.isEmpty());
        assertTrue(oliver.encounterReviewed.isEmpty());assertTrue(effects.facts.isEmpty());assertNull(oliver.pending);
    }
    @Test void rejectedPublicResponsesPreserveTheHeardCallAndListenerPose() {
        for(ActionType response:List.of(ActionType.ACKNOWLEDGE,ActionType.DECLINE_CALL)) {
            State s=world();Resident oliver=s.agents.get("oliver"),theo=s.agents.get("theo");
            Engine.apply(s,theo,new Command("call",ActionType.CALL_OUT,"oliver","Hello Oliver!",null,null,null),"call",new Engine.Effects());
            PublicCall call=s.publicCalls.get("call");Engine.Effects effects=new Engine.Effects();
            assertThrows(Engine.Invalid.class,()->Engine.apply(s,oliver,
                new Command("response",response,"theo","I'm here with Aisha and Diego.",null,null,null),"response",effects));
            assertEquals("pending",call.status);assertNull(call.response);assertNull(call.responseMinute);
            assertTrue(oliver.encounterReviewed.isEmpty());assertNull(oliver.facingHeading);assertEquals(0,oliver.facingUntil);
            assertTrue(effects.facts.isEmpty());assertTrue(s.conversations.isEmpty());assertNull(oliver.pending);
        }
    }
}
