package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

public final class Conversations {
    private Conversations() {}
    public static String key(String a,String b) {return a.compareTo(b)<0?a+":"+b:b+":"+a;}
    public static void speak(State s,Resident speaker,Resident listener,String message) {
        Engine.require(!listener.paused&&!Engine.busy(listener),"This resident is unavailable for conversation; choose another activity");
        String key=key(speaker.id,listener.id);Conversation c=s.conversations.get(key);
        Engine.require(c==null||closed(s,c)||!speaker.id.equals(c.lastSpeaker),"Waiting for "+listener.name+" to reply. No reply has been received; end this chat or choose another activity");
        String normalized=normalize(message);
        Engine.require(c==null||c.turns.stream().noneMatch(t->speaker.id.equals(t.speaker())&&s.minute-t.minute()<1440&&normalize(t.message()).equals(normalized)),
            "You already said this to "+listener.name+". Read their latest turn and respond to it with new content, acknowledge a goodbye, or END_CHAT; do not repeat your introduction or previous answer");
        if(c==null) {c=new Conversation();c.a=speaker.id;c.b=listener.id;c.closed=true;s.conversations.put(key,c);}
        if(closed(s,c)) {c.initiator=speaker.id;c.engaged=false;}
        else {
            // Old saves have no recorded consent for this session. A real new reply can establish it.
            if(c.initiator==null)c.initiator=c.lastSpeaker;
            if(!speaker.id.equals(c.initiator))c.engaged=true;
        }
        c.turnSequence++;c.closed=false;c.closingSpeaker=null;c.lastSpeaker=speaker.id;c.lastMinute=s.minute;c.turns.add(new Turn(speaker.id,message,s.minute,speaker.location));
        while(c.turns.size()>12)c.turns.removeFirst();
    }
    // status controls turn-taking, not whether the social interaction has ended.
    public record View(String other,String status,String lastSpeaker,long lastMinute,List<Turn> turns,List<ConversationTravel> travel) {}
    private static String normalize(String message) {return message.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").strip();}
    public record Focus(String other,String status,Turn latestTurn,Turn lastOwnTurn) {}
    public static List<Focus> focus(List<View> views,String self,Set<String> nearby) {
        return views.stream().filter(v->nearby.contains(v.other())&&!v.turns().isEmpty())
            .sorted(Comparator.comparingLong(View::lastMinute).reversed()).map(v->new Focus(v.other(),v.status(),v.turns().getLast(),
                v.turns().stream().filter(t->self.equals(t.speaker())).reduce((a,b)->b).orElse(null))).toList();
    }
    static void startedTravel(State s,Resident a,String origin) {
        Pending p=a.pending;
        String destination=p.finalDestination!=null?p.finalDestination:p.target;
        // Movement already labels the actor as on the road. Use the origin room for witnessed departures.
        Resident departure=new Resident();departure.id=a.id;departure.location=origin;departure.x=a.x;departure.z=a.z;departure.pending=a.pending;
        for(Conversation c:s.conversations.values()) {
            if(!a.id.equals(c.a)&&!a.id.equals(c.b))continue;
            Resident other=s.agents.get(a.id.equals(c.a)?c.b:c.a);
            // Retain departures witnessed just after an exchange, not a contact's remote movements.
            if(other==null||s.minute-c.lastMinute>=60||Engine.distance(a.x,a.z,other.x,other.z)>10||!Social.perceivable(s,departure,other))continue;
            c.travel.add(new ConversationTravel(a.id,p.id,origin,destination,s.minute,null,"travelling"));
            while(c.travel.size()>8)c.travel.removeFirst();
        }
    }
    static void travelResults(State s,Engine.Effects e) {
        if(e.completions.isEmpty())return;
        for(Conversation c:s.conversations.values())for(int i=0;i<c.travel.size();i++) {
            ConversationTravel trip=c.travel.get(i);
            if(!trip.status().equals("travelling"))continue;
            for(Engine.Completion completion:e.completions)if(trip.actionId().equals(completion.id())) {
                Resident resident=s.agents.get(trip.resident());
                Long arrival=completion.status().equals("completed")&&resident!=null&&trip.destination().equals(resident.location)?s.minute:null;
                c.travel.set(i,new ConversationTravel(trip.resident(),trip.actionId(),trip.origin(),trip.destination(),trip.departedMinute(),arrival,completion.status()));
            }
        }
    }
    static boolean closed(State s,Conversation c) {
        Resident a=s.agents.get(c.a),b=s.agents.get(c.b);
        return c.closed||s.minute-c.lastMinute>=60||a==null||b==null||Engine.distance(a.x,a.z,b.x,b.z)>10||!Social.perceivable(s,a,b);
    }
    public static void end(State s,Resident a) {
        s.conversations.values().stream().filter(c->a.id.equals(c.a)||a.id.equals(c.b)).forEach(c->c.closed=true);
    }
    public static boolean active(State s,Resident a) {
        return s.conversations.values().stream().anyMatch(c->(a.id.equals(c.a)||a.id.equals(c.b))&&!closed(s,c)
            &&(c.engaged||a.id.equals(c.initiator==null?c.lastSpeaker:c.initiator)));
    }
    /** A closing is an actual utterance, allowed even while awaiting a reply. */
    public static void farewell(State s,Resident a,String target,String message,Engine.Effects e) {
        List<Conversation> chats=s.conversations.values().stream()
            .filter(c->a.id.equals(c.a)||a.id.equals(c.b))
            .filter(c->target.isEmpty()?!closed(s,c):target.equals(a.id.equals(c.a)?c.b:c.a)).toList();
        Engine.require(target.isEmpty()||!chats.isEmpty(),"Choose a resident you have spoken to before closing the chat");
        Engine.require(chats.isEmpty()||Engine.present(message),"Say a brief farewell with END_CHAT message before ending the conversation");
        // Validate every recipient before changing any thread or releasing a journey.
        for(Conversation c:chats) {
            Engine.together(a,s.agents.get(a.id.equals(c.a)?c.b:c.a));
            Engine.require(Social.perceivable(s,a,s.agents.get(a.id.equals(c.a)?c.b:c.a)),"The other resident must be in the same visible space");
            Engine.require(!closed(s,c)||(c.closingSpeaker!=null&&c.closingSpeaker.equals(c.lastSpeaker)
                &&!a.id.equals(c.closingSpeaker)&&s.minute-c.lastMinute<60),
                "This chat has already ended; do not repeat a farewell");
        }
        for(Conversation c:chats) {
            Resident other=s.agents.get(a.id.equals(c.a)?c.b:c.a);
            if(!closed(s,c))c.closingSpeaker=a.id;
            c.turnSequence++;c.lastSpeaker=a.id;c.lastMinute=s.minute;
            c.turns.add(new Turn(a.id,message.strip(),s.minute,a.location));
            while(c.turns.size()>12)c.turns.removeFirst();
            c.closed=true;
            e.facts.add(new Engine.Fact("conversation",a.id,other.id,a.name+" to "+other.name+": "+message.strip()));
        }
    }
    static void tick(State s) {
        // Keep an ended session closed even if its participants meet again later.
        s.conversations.values().stream().filter(c->closed(s,c)).forEach(c->c.closed=true);
    }
    static String status(State s,Resident a,Conversation c,boolean available) {
        return !available?"unavailable":closed(s,c)?"closed":a.id.equals(c.lastSpeaker)?"waiting_for_reply":"your_turn";
    }
    public static List<View> views(State s,Resident a,Map<String,Boolean> availability) {
        return s.conversations.values().stream().filter(c->a.id.equals(c.a)||a.id.equals(c.b)).map(c->{
            String other=a.id.equals(c.a)?c.b:c.a;
            String status=status(s,a,c,availability.getOrDefault(other,true));
            return new View(other,status,c.lastSpeaker,c.lastMinute,List.copyOf(c.turns),List.copyOf(c.travel));
        }).toList();
    }
}
