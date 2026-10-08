package world.earth35.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.util.*;
import world.earth35.world.State.*;

/** Checks learning prerequisites and evidence; authored analyses and works remain unverified drafts. */
public final class Learning {
    private Learning() {}
    private static final ObjectMapper JSON=new ObjectMapper();
    public static final int RECORD_LIMIT=100;
    private static SubjectKnowledge knowledge(Resident a,String subject) {
        Library.refreshKnowledge(a);
        SubjectKnowledge knowledge=a.knowledge.get(subject);
        Engine.require(knowledge!=null,"Read books about this subject before reasoning or creating work");
        return knowledge;
    }
    private static JsonNode payload(String message) {
        Engine.require(message!=null&&message.length()<=8000,"Provide an authored JSON message of at most 8000 characters");
        try {
            JsonNode value=JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature()).readTree(message);
            Engine.require(value!=null&&value.isObject(),"Message must be a JSON object");
            return value;
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new Engine.Invalid("Message must be valid JSON with the required reasoning or creation fields");
        }
    }
    private static String text(JsonNode value,String field,int minimum,int maximum) {
        JsonNode node=value.path(field);
        Engine.require(node.isTextual(),"Provide text for "+field);
        String result=node.asText().strip();
        Engine.require(result.length()>=minimum&&result.length()<=maximum,
            field+" must contain "+minimum+" to "+maximum+" characters");
        return result;
    }
    static String reason(State s,Resident a,String subject,String message,String id,Engine.Effects effects) {
        SubjectKnowledge knowledge=knowledge(a,subject);
        Engine.require(knowledge.getCanReason(),"Reasoning requires 12 knowledge points and four learned concepts in this subject; study more distinct lessons");
        Engine.require(a.reasoning.size()<RECORD_LIMIT,"The private reasoning record limit is 100");
        JsonNode value=payload(message);
        String problem=text(value,"problem",20,500),analysis=text(value,"analysis",80,6000);
        JsonNode evidence=value.path("concepts");
        Engine.require(evidence.isArray()&&evidence.size()>=2&&evidence.size()<=6,"Cite two to six exact learned concepts in concepts");
        Set<String> concepts=new LinkedHashSet<>();
        for(JsonNode node:evidence) {
            Engine.require(node.isTextual()&&knowledge.concepts.contains(node.asText()),"Every cited concept must already be learned in this subject");
            Engine.require(analysis.contains(node.asText()),"Explain the application of each cited concept in analysis, including its exact text");
            concepts.add(node.asText());
        }
        Engine.require(concepts.size()>=2,"Cite at least two distinct learned concepts");
        Engine.require(a.reasoning.values().stream().noneMatch(r->r.subject().equals(subject)&&r.problem().equals(problem)&&r.analysis().equals(analysis)),"This reasoning is already recorded; develop a new problem or analysis");
        Reasoning reasoning=new Reasoning(id,subject,problem,analysis,List.copyOf(concepts),s.minute);
        a.reasoning.put(id,reasoning);
        // Full content is private. A public activity event carries no problem, analysis or citations.
        effects.facts.add(new Engine.Fact("reasoning",a.id,subject,a.name+" developed a private analysis using learned "+subject+" concepts."));
        return "Recorded reasoning "+id+" about "+subject+" grounded in "+concepts.size()+" learned concepts; conclusions still need validation";
    }
    static String create(State s,Resident a,String subject,String kind,String message,String id,Engine.Effects effects) {
        SubjectKnowledge knowledge=knowledge(a,subject);
        Engine.require(knowledge.getCanCreate(),"Creation requires 28 knowledge points and four intermediate or advanced concepts in this subject");
        Engine.require(List.of("product","business","research","art","writing","design").contains(kind==null?"":kind),"Choose work kind product, business, research, art, writing or design");
        Engine.require(a.creations.size()<RECORD_LIMIT,"The private creation record limit is 100");
        JsonNode value=payload(message);
        String title=text(value,"title",5,120),content=text(value,"content",120,6000),reasoningId=text(value,"reasoningId",1,80);
        Reasoning reasoning=a.reasoning.get(reasoningId);
        Engine.require(reasoning!=null&&reasoning.subject().equals(subject),"Use an existing reasoningId belonging to you and this subject");
        Engine.require(reasoning.concepts().stream().anyMatch(content::contains),"Connect this work to its reasoning by applying at least one cited concept, including its exact text in content");
        Engine.require(a.creations.values().stream().noneMatch(work->work.subject().equals(subject)&&work.kind().equals(kind)&&work.content().equals(content)),"This work is already saved; develop a different original draft");
        a.creations.put(id,new Creation(id,subject,kind,title,content,reasoningId,reasoning.concepts(),s.minute));
        effects.facts.add(new Engine.Fact("creation",a.id,subject,a.name+" developed a private "+kind+" draft using "+subject+" reasoning."));
        return "Saved "+kind+" draft "+id+" based on reasoning "+reasoningId+"; evaluate and test it before treating it as a finished outcome";
    }
}
