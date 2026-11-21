package world.earth35.world;

import java.util.*;
import java.io.IOException;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import world.earth35.world.State.*;

/** Private, directed interpretations of real interactions. The world records facts, never feelings. */
public final class Relationships {
    private Relationships() {}
    public static final int EVIDENCE_LIMIT=12,HISTORY_LIMIT=8,CONTEXT_LIMIT=16;
    public static final String ADVICE="relationships is your private, one-sided relationship journal. Each entry separates actual interaction evidence from your own appraisal. "
        +"When an interaction matters to you, optionally include cognition.relationship={target,label,feeling,reason,evidence:[actual evidence IDs],warmth,trust,respect,closeness,attraction,tension}. "
        +"feeling (up to 160 characters) and reason (up to 600) describe your own response; cite one to six evidence IDs from that person's entry. label is optional (up to 100). "
        +"Each optional dimension is an integer 0..100: high warmth means fondness, trust means confidence in reliability, respect means esteem, closeness means emotional connection, attraction means personal attraction, and tension means strain. Null leaves a dimension unchanged; unknown dimensions have no default score. "
        +"Let personality, values, memories and the meaning of specific interactions shape your response. Warmth can coexist with distrust, admiration with irritation, and attraction need not be present or reciprocal. "
        +"Keep continuity with earlier appraisals: ordinary familiarity can grow gradually, while a meaningful hurt or repair may matter more. Do not reset feelings at each turn or mistake repeated contact for intimacy. "
        +"Friendship, estrangement, repair and affection may develop through experience; repeated greetings or gifts do not mechanically earn them. A declined invitation, an expired plan or silence does not establish rejection, betrayal or another person's motive. "
        +"needsReflection is an optional cue, never an obligation. Consider past appraisals and time apart when you meet again; time alone does not erase a bond. You may reconsider the same evidence without inventing a new event. "
        +"Use your feelings when choosing whom to approach, help, confide in, give space to or repair things with. Your appraisal cannot determine their feelings, consent, private thoughts or actions. ";

    @Schema(name="RelationshipEvidence")
    public record Evidence(String id,String kind,String actor,String description,long minute) {}
    @Schema(name="RelationshipAppraisal")
    public record Appraisal(String label,String feeling,String reason,List<String> evidence,
                            Integer warmth,Integer trust,Integer respect,Integer closeness,Integer attraction,Integer tension) {}
    @Schema(name="RelationshipAppraisalInput")
    public record Draft(String target,String label,String feeling,String reason,List<String> evidence,
                        @JsonDeserialize(using=Score.class) Integer warmth,@JsonDeserialize(using=Score.class) Integer trust,
                        @JsonDeserialize(using=Score.class) Integer respect,@JsonDeserialize(using=Score.class) Integer closeness,
                        @JsonDeserialize(using=Score.class) Integer attraction,@JsonDeserialize(using=Score.class) Integer tension) {}
    public static class Score extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT)return context.reportInputMismatch(Integer.class,"Relationship dimensions must be integer numbers");
            return parser.getIntValue();
        }
    }
    @Schema(name="RelationshipReflection")
    public record Reflection(long minute,Appraisal appraisal,List<Evidence> evidence) {}
    @Schema(name="RelationshipBond")
    public static class Bond {
        public String name;
        public int interactionCount;
        public long lastInteractionMinute,appraisedMinute;
        public Appraisal appraisal;
        public List<Evidence> evidence=new ArrayList<>();
        public List<Reflection> history=new ArrayList<>();
        public Set<String> reviewedEvidence=new LinkedHashSet<>();
    }
    @Schema(name="RelationshipView")
    public record View(String other,String name,String label,int interactionCount,long lastInteractionMinute,
                       long minutesSinceInteraction,boolean needsReflection,Appraisal appraisal,long appraisedMinute,
                       List<Evidence> evidence,List<Reflection> history) {}

    /** Only call after a validated, experienced interaction; never on mere proximity or a claimed outcome. */
    public static void record(State s,String id,String kind,String actor,String other,String description) {
        Resident a=s.agents.get(actor),b=s.agents.get(other);
        if(a==null||b==null||a.id.equals(b.id))return;
        String speaker=Set.of("plan_expired","plan_interrupted","plan_completed","shared_activity").contains(kind)?null:actor;
        Evidence evidence=new Evidence(id,kind,speaker,description.length()>400?description.substring(0,397)+"...":description,s.minute);
        remember(a,b,evidence);remember(b,a,evidence);
    }
    private static void remember(Resident a,Resident other,Evidence evidence) {
        Bond bond=a.bonds.computeIfAbsent(other.id,ignored->new Bond());bond.name=other.name;
        if(bond.evidence.stream().anyMatch(old->old.id().equals(evidence.id())))return;
        bond.evidence.add(evidence);bond.interactionCount++;bond.lastInteractionMinute=evidence.minute();
        while(bond.evidence.size()>EVIDENCE_LIMIT)bond.evidence.removeFirst();
        bond.reviewedEvidence.retainAll(bond.evidence.stream().map(Evidence::id).toList());
    }
    public static boolean experienced(State s,Resident a,String other) {
        return s.agents.containsKey(other)&&!a.id.equals(other)&&(a.bonds.containsKey(other)
            ||s.conversations.containsKey(Conversations.key(a.id,other))||a.sharedExperienceCounts.getOrDefault(other,0)>0);
    }
    public static void label(State s,Resident a,String other,String label) {
        Engine.require(experienced(s,a,other),"Interact with this resident before recording a relationship");
        a.relationships.put(other,text(label,100,"relationship label",true));
    }
    /** Validation is separate so a combined cognition update can be atomic. */
    public static Draft validate(State s,Resident a,Draft draft) {
        Engine.require(draft!=null,"Provide a relationship appraisal");
        String target=text(draft.target(),80,"relationship target",true);
        Engine.require(s.agents.containsKey(target)&&!target.equals(a.id),"Choose another resident");
        Bond bond=a.bonds.get(target);
        Engine.require(bond!=null&&!bond.evidence.isEmpty(),"Ground this relationship in your own recorded interactions with this person");
        String label=text(draft.label(),100,"relationship label",false),feeling=text(draft.feeling(),160,"relationship feeling",true),
            reason=text(draft.reason(),600,"relationship reason",true);
        Engine.require(draft.evidence()!=null&&!draft.evidence().isEmpty()&&draft.evidence().size()<=6,"Cite one to six relationship evidence IDs");
        List<String> sources=new ArrayList<>();
        for(String id:draft.evidence()) {
            text(id,160,"evidence ID",true);
            Engine.require(available(bond).containsKey(id),"Cite your own available evidence for this specific person");
            Engine.require(!sources.contains(id),"Cite each relationship evidence ID once");sources.add(id);
        }
        for(Integer score:Arrays.asList(draft.warmth(),draft.trust(),draft.respect(),draft.closeness(),draft.attraction(),draft.tension()))
            Engine.require(score==null||score>=0&&score<=100,"Relationship dimensions must be integers from 0 to 100, or null");
        return new Draft(target,label,feeling,reason,List.copyOf(sources),draft.warmth(),draft.trust(),draft.respect(),draft.closeness(),draft.attraction(),draft.tension());
    }
    public static void update(State s,Resident a,Draft draft) {
        Engine.require(!a.paused&&ActionPerformance.awake(a),"Review relationships while awake");
        apply(s,a,validate(s,a,draft));
    }
    static void apply(State s,Resident a,Draft draft) {
        Bond bond=a.bonds.get(draft.target());Appraisal old=bond.appraisal;
        String label=draft.label()==null?a.relationships.get(draft.target()):draft.label();
        Appraisal next=new Appraisal(label,draft.feeling(),draft.reason(),draft.evidence(),
            keep(draft.warmth(),old==null?null:old.warmth()),keep(draft.trust(),old==null?null:old.trust()),
            keep(draft.respect(),old==null?null:old.respect()),keep(draft.closeness(),old==null?null:old.closeness()),
            keep(draft.attraction(),old==null?null:old.attraction()),keep(draft.tension(),old==null?null:old.tension()));
        if(next.equals(old))return;
        bond.appraisal=next;bond.appraisedMinute=s.minute;
        bond.reviewedEvidence.addAll(draft.evidence());
        if(label!=null)a.relationships.put(draft.target(),label);
        Map<String,Evidence> sources=available(bond);
        bond.history.add(new Reflection(s.minute,next,draft.evidence().stream().map(sources::get).toList()));
        while(bond.history.size()>HISTORY_LIMIT)bond.history.removeFirst();
    }
    private static Integer keep(Integer next,Integer old) {return next==null?old:next;}
    private static Map<String,Evidence> available(Bond bond) {
        Map<String,Evidence> result=new LinkedHashMap<>();
        for(Reflection reflection:bond.history)for(Evidence evidence:reflection.evidence())result.put(evidence.id(),evidence);
        for(Evidence evidence:bond.evidence)result.put(evidence.id(),evidence);
        return result;
    }
    private static String text(String value,int limit,String name,boolean required) {
        Engine.require(value!=null||!required,"Provide "+name);
        Engine.require(value==null||!value.isBlank()&&value.length()<=limit,"Keep "+name+" nonblank and within "+limit+" characters");
        return value==null?null:value.strip();
    }
    public static List<View> views(Resident a,long minute) {
        return views(a,minute,Set.of());
    }
    public static List<View> views(Resident a,long minute,Set<String> nearby) {
        Map<String,Bond> known=new LinkedHashMap<>(a.bonds);
        a.relationships.keySet().forEach(id->known.putIfAbsent(id,new Bond()));
        return known.entrySet().stream().sorted(Comparator.<Map.Entry<String,Bond>>comparingInt(e->nearby.contains(e.getKey())?0:1)
            .thenComparing(Comparator.<Map.Entry<String,Bond>>comparingLong(e->e.getValue().lastInteractionMinute).reversed())
            .thenComparing(Map.Entry::getKey)).limit(CONTEXT_LIMIT).map(entry->{
                Bond bond=entry.getValue();Appraisal appraisal=bond.appraisal;
                List<Evidence> recent=List.copyOf(bond.evidence.subList(Math.max(0,bond.evidence.size()-6),bond.evidence.size()));
                boolean due=recent.stream().anyMatch(e->!bond.reviewedEvidence.contains(e.id()));
                return new View(entry.getKey(),Objects.toString(bond.name,entry.getKey()),a.relationships.get(entry.getKey()),bond.interactionCount,bond.lastInteractionMinute,
                    bond.interactionCount==0?0:Math.max(0,minute-bond.lastInteractionMinute),due,appraisal,bond.appraisedMinute,
                    recent,
                    List.copyOf(bond.history.subList(Math.max(0,bond.history.size()-2),bond.history.size())));
            }).toList();
    }
}
