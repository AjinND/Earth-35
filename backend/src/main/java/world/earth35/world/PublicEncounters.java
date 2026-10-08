package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Public attention requests are perceived opportunities, never consent to a conversation. */
public final class PublicEncounters {
    private PublicEncounters() {}
    private static final int CALL_MINUTES=10,PAIR_COOLDOWN=30;

    public static void callOut(State s,Resident caller,String target,String message,String id,Engine.Effects e) {
        Resident listener=s.agents.get(target);
        Engine.require(listener!=null&&!caller.id.equals(target),"Choose another nearby resident to call out to");
        Engine.require(Social.outdoors(s,caller)&&Social.outdoors(s,listener),"Call out only to another resident in public outdoor space");
        Engine.require(Social.perceivable(s,caller,listener)&&Engine.distance(caller.x,caller.z,listener.x,listener.z)<=20,
            "The resident must be within twenty units and hear you in the same unobstructed public space");
        Engine.require(Social.available(listener),"This resident is unavailable to hear a public call");
        Engine.require(Engine.present(message),"Say what you are calling out");
        Engine.require(message.length()<=1000,"Keep the public call within 1000 characters");
        Engine.require(s.publicCalls.values().stream().noneMatch(call->caller.id.equals(call.caller)&&listener.id.equals(call.listener)
            &&s.minute-call.minute<PAIR_COOLDOWN),"Give this resident time to respond or continue on their way before calling again");
        PublicCall call=new PublicCall();call.id=id;call.caller=caller.id;call.listener=listener.id;
        s.publicCalls.values().removeIf(previous->caller.id.equals(previous.caller)&&listener.id.equals(previous.listener));
        call.message=message.strip();call.minute=s.minute;call.expires=s.minute+CALL_MINUTES;s.publicCalls.put(id,call);
        e.facts.add(new Engine.Fact("public_call",caller.id,listener.id,caller.name+" called out to "+listener.name+": "+call.message));
        Social.reviewed(s,caller);
    }

    public static void acknowledge(State s,Resident listener,String caller,String message,Engine.Effects e) {
        respond(s,listener,caller,message,true,e);
    }
    public static void decline(State s,Resident listener,String caller,String message,Engine.Effects e) {
        respond(s,listener,caller,message,false,e);
    }
    private static void respond(State s,Resident listener,String callerId,String message,boolean acknowledge,Engine.Effects e) {
        PublicCall call=s.publicCalls.values().stream().filter(c->listener.id.equals(c.listener)&&callerId.equals(c.caller)
                &&c.status.equals("pending")&&s.minute<c.expires)
            .max(Comparator.comparingLong(c->c.minute)).orElse(null);
        Engine.require(call!=null,"Choose a resident whose recent public call you have heard and have not answered");
        Resident caller=s.agents.get(callerId);
        Engine.require(message==null||message.length()<=1000,"Keep the public response within 1000 characters");
        // A heard call can be declined silently after the caller has left. A visible acknowledgement needs its recipient.
        if(acknowledge||Engine.present(message)) {
            Engine.require(Social.outdoors(s,listener)&&Social.outdoors(s,caller)&&Social.perceivable(s,listener,caller)
                &&Engine.distance(listener.x,listener.z,caller.x,caller.z)<=20,"The caller is no longer within sight or hearing; continue on your way or decline silently");
        }
        call.status=acknowledge?"acknowledged":"declined";
        call.response=Engine.present(message)?message.strip():null;call.responseMinute=s.minute;
        if(acknowledge) {
            listener.facingHeading=Math.atan2(caller.x-listener.x,caller.z-listener.z);listener.facingUntil=s.minute+2;
        }
        if(acknowledge||call.response!=null) {
            String fact=call.response!=null?listener.name+" to "+caller.name+": "+call.response
                :listener.name+" turned toward "+caller.name+" and acknowledged the call.";
            e.facts.add(new Engine.Fact("public_call_response",listener.id,callerId,fact));
        }
        listener.encounterReviewed.put("call:"+call.id,s.minute);
    }

    public static List<PublicCall> calls(State s,Resident a) {
        return s.publicCalls.values().stream().filter(call->a.id.equals(call.caller)||a.id.equals(call.listener))
            .filter(call->call.expires>s.minute-10).sorted(Comparator.comparingLong((PublicCall call)->call.minute).reversed())
            .map(call->view(s,call,a)).toList();
    }
    private static PublicCall view(State s,PublicCall call,Resident viewer) {
        PublicCall view=new PublicCall();view.id=call.id;view.caller=call.caller;view.listener=call.listener;
        view.message=call.message;view.minute=call.minute;view.expires=call.expires;view.status=call.status;
        view.response=call.response;view.responseMinute=call.responseMinute;
        if(viewer.id.equals(call.caller)&&(call.status.equals("ignored")||call.status.equals("declined")&&call.response==null)) {
            view.status=s.minute<call.expires?"pending":"expired";view.responseMinute=null;
        }
        return view;
    }
    public static boolean decisionNeeded(State s,Resident a) {
        return calls(s,a).stream().anyMatch(call->a.id.equals(call.listener)&&call.status.equals("pending")
            &&s.minute<call.expires&&!a.encounterReviewed.containsKey("call:"+call.id)
            ||a.id.equals(call.caller)&&observableResponse(call)&&!a.encounterReviewed.containsKey("response:"+call.id));
    }
    private static boolean observableResponse(PublicCall call) {
        return "acknowledged".equals(call.status)||"declined".equals(call.status)&&Engine.present(call.response);
    }
    /** Actual responses offer a new choice; routine review never invents or consumes a pending call. */
    public static void reviewResponses(State s,Resident a) {
        for(PublicCall call:s.publicCalls.values())if(a.id.equals(call.caller)&&observableResponse(call))
            a.encounterReviewed.put("response:"+call.id,s.minute);
    }
    /** Ignoring a call is a private choice and has no public utterance or effect on a journey. */
    public static void ignore(State s,Resident a) {
        for(PublicCall call:s.publicCalls.values())if(a.id.equals(call.listener)&&call.status.equals("pending")&&s.minute<call.expires) {
            call.status="ignored";call.responseMinute=s.minute;a.encounterReviewed.put("call:"+call.id,s.minute);
        }
    }
    public static void tick(State s) {
        for(PublicCall call:s.publicCalls.values())if(call.status.equals("pending")&&s.minute>=call.expires)call.status="expired";
        s.publicCalls.values().removeIf(call->call.minute<s.minute-1440);
        for(Resident a:s.agents.values()) {
            if(a.facingUntil<=s.minute) {a.facingHeading=null;a.facingUntil=0;}
            a.encounterReviewed.keySet().removeIf(key->key.startsWith("call:")&&!s.publicCalls.containsKey(key.substring(5))
                ||key.startsWith("response:")&&!s.publicCalls.containsKey(key.substring(9)));
        }
    }
}
