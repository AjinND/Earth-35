package world.earth35.protocol;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.protocol.HostedAgentService.Decision;
import world.earth35.protocol.HostedAgentService.ProviderFailure;

/** Parses only the final answer; never treats internal reasoning as an executable action. */
final class HostedDecisionParser {
    private HostedDecisionParser() {}

    static Decision parse(ObjectMapper json,JsonNode response) {
        long tokens=HostedAgentService.usage(response);
        String content=response.path("choices").path(0).path("message").path("content").asText("").strip();
        // Some compatible endpoints put a completed thinking block in content instead
        // of reasoning_content. Discard it entirely, even if it contains example JSON.
        if(content.startsWith("<think>")) {
            int end=content.indexOf("</think>");
            if(end<0) throw invalid("The model returned reasoning without a final answer.",tokens);
            content=content.substring(end+8).strip();
        }
        if(content.startsWith("```")) content=content.replaceFirst("(?i)^```(?:json)?\\s*","").replaceFirst("\\s*```$","").strip();
        // Tolerate a short prose wrapper around exactly one object. The strict reader
        // below still rejects multiple objects, duplicate keys and incomplete JSON.
        int first=content.indexOf('{'),last=content.lastIndexOf('}');
        if(first>=0&&last>=first&&!content.startsWith("{")&&!content.startsWith("[")
            &&!content.contains("<think>")&&!content.contains("</think>")
            &&!content.substring(0,first).matches("(?s).*[\\[\\]<>].*")
            &&!content.substring(last+1).matches("(?s).*[\\[\\]<>].*")) content=content.substring(first,last+1);
        JsonNode choice;
        try {
            choice=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature()).readTree(content);
        } catch(Exception e) {throw invalid("The model returned invalid JSON. A single complete action object is required.",tokens);}
        if(choice==null||!choice.isObject()) throw invalid("The model returned no action object.",tokens);
        String name=text(choice,"action");
        if(name==null||name.isBlank()) throw invalid("The model omitted the action name.",tokens);
        ActionType action;
        try {action=ActionType.valueOf(name.strip().toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException e) {throw invalid("The model chose an unsupported world action.",tokens);}
        for(String field:List.of("target","message","item")) {
            JsonNode value=choice.path(field);
            int limit=field.equals("message")?(action==ActionType.REASON||action==ActionType.CREATE_WORK?8000:1000):80;
            if(present(value)&&(!value.isTextual()||value.textValue().length()>limit))
                throw invalid("The model returned an invalid "+field+" field.",tokens);
        }
        int minimumQuantity=action==ActionType.START_BUSINESS?0:1;
        if(present(choice.path("quantity"))&&(integer(choice,"quantity")==null||integer(choice,"quantity")<minimumQuantity||integer(choice,"quantity")>100))
            throw invalid("The model returned an invalid quantity; use an integer from "+minimumQuantity+" to 100.",tokens);
        if(present(choice.path("amount"))&&(longValue(choice,"amount")==null||longValue(choice,"amount")<1||longValue(choice,"amount")>1_000_000))
            throw invalid("The model returned an invalid amount; use integer cents from 1 to 1000000.",tokens);
        Integer loanYears=integer(choice,"loanYears");String laborMode=text(choice,"laborMode");
        if(present(choice.path("loanYears"))&&(loanYears==null||!List.of(3,5).contains(loanYears)))
            throw invalid("The model returned an invalid loanYears; choose 3 or 5 years, or null.",tokens);
        if(present(choice.path("laborMode"))&&(laborMode==null||!List.of("paid","self").contains(laborMode)))
            throw invalid("The model returned an invalid laborMode; choose paid, self, or null.",tokens);
        Command command=new Command(UUID.randomUUID().toString(),action,text(choice,"target"),text(choice,"message"),text(choice,"item"),integer(choice,"quantity"),longValue(choice,"amount"),loanYears,laborMode);
        JsonNode remembered=choice.path("memory"),plan=choice.path("nightPlan"),preference=choice.path("routine");
        // Optional annotations must not discard an otherwise valid action. World-dependent
        // routine/plan validation still happens under the world lock in AgentService.
        NightPlanInput nightPlan=null;
        String reason=text(plan,"reason");Long until=longValue(plan,"until");
        if(reason!=null&&until!=null) nightPlan=new NightPlanInput(reason,until);
        RoutineInput routine=null;
        Integer wake=integer(preference,"wakeMinute"),bed=integer(preference,"sleepMinute");String morning=text(preference,"morningActivity");
        if(wake!=null&&bed!=null&&morning!=null) routine=new RoutineInput(wake,bed,morning);
        List<HostedAgentService.MemoryDraft> memories=new ArrayList<>();
        if(choice.path("memories").isArray())for(JsonNode draft:choice.path("memories")) {
            if(memories.size()>=3)break;
            String kind=text(draft,"kind"),body=text(draft,"content");
            JsonNode tags=draft.path("entities"),sources=draft.path("sourceEvents");
            if(!List.of("episodic","semantic").contains(kind==null?"":kind)||body==null||body.isBlank()||body.length()>4000
                ||!tags.isArray()||tags.size()>20||!sources.isArray()||sources.size()>20)continue;
            List<String> entities=new ArrayList<>();List<Long> events=new ArrayList<>();boolean valid=true;
            for(JsonNode tag:tags){if(!tag.isTextual()||tag.asText().isBlank()||tag.asText().length()>80){valid=false;break;}entities.add(tag.asText());}
            for(JsonNode source:sources){if(!source.isIntegralNumber()||!source.canConvertToLong()||source.asLong()<=0){valid=false;break;}events.add(source.asLong());}
            if(valid)memories.add(new HostedAgentService.MemoryDraft(kind,body,List.copyOf(entities),List.copyOf(events)));
        }
        String legacy=text(remembered,"content");Long source=longValue(remembered,"sourceEventId");
        if(memories.isEmpty()&&legacy!=null&&source!=null)memories.add(new HostedAgentService.MemoryDraft("episodic",legacy,List.of(),List.of(source)));
        return new Decision(command,legacy,source,tokens,nightPlan,routine,List.copyOf(memories));
    }
    private static ProviderFailure invalid(String message,long tokens) {return new ProviderFailure(message,false,tokens,true);}
    private static boolean present(JsonNode value) {return !value.isMissingNode()&&!value.isNull();}
    private static String text(JsonNode node,String field) {JsonNode value=node.path(field);return value.isTextual()?value.textValue():null;}
    private static Integer integer(JsonNode node,String field) {JsonNode value=node.path(field);return value.isIntegralNumber()&&value.canConvertToInt()?value.intValue():null;}
    private static Long longValue(JsonNode node,String field) {JsonNode value=node.path(field);return value.isIntegralNumber()&&value.canConvertToLong()?value.longValue():null;}
}
