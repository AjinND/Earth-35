package world.earth35.protocol;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.protocol.HostedAgentService.Decision;
import world.earth35.protocol.HostedAgentService.ProviderFailure;
import world.earth35.world.Cognition;

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
        SharedPlanInput sharedPlan=null;
        if(present(choice.path("sharedPlan"))) {
            if(action!=ActionType.TALK&&action!=ActionType.END_CHAT)
                throw invalid("A shared agreement must accompany your own TALK or END_CHAT turn.",tokens);
            JsonNode agreement=choice.path("sharedPlan");
            validateAgreement(agreement,tokens);
            try {sharedPlan=json.treeToValue(agreement,SharedPlanInput.class);}
            catch(Exception e) {throw invalid("The model returned an invalid shared agreement.",tokens);}
        }
        Command command=new Command(UUID.randomUUID().toString(),action,text(choice,"target"),text(choice,"message"),text(choice,"item"),integer(choice,"quantity"),longValue(choice,"amount"),loanYears,laborMode,sharedPlan);
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
        Cognition.Update cognition=null;
        if(choice.path("cognition").isObject()) {
            try {cognition=json.treeToValue(choice.path("cognition"),Cognition.Update.class);}
            catch(Exception ignored) { /* An optional self-assessment must not discard a valid world action. */ }
        }
        return new Decision(command,legacy,source,tokens,nightPlan,routine,List.copyOf(memories),cognition);
    }
    private static ProviderFailure invalid(String message,long tokens) {return new ProviderFailure(message,false,tokens,true);}
    private static void validateAgreement(JsonNode agreement,long tokens) {
        if(!agreement.isObject()||!List.of("propose","accept","decline","cancel").contains(Objects.toString(text(agreement,"operation"),"")))
            throw invalid("Choose an explicit shared agreement operation: propose, accept, decline or cancel.",tokens);
        validateFields(agreement,Set.of("operation","plan","destination","activity","delayMinutes","durationMinutes","tasks"),tokens);
        for(String field:List.of("plan","destination","activity"))validateText(agreement,field,field.equals("activity")?1000:80,tokens);
        for(String field:List.of("delayMinutes","durationMinutes"))validateMinutes(agreement,field,tokens);
        JsonNode tasks=agreement.path("tasks");
        if(present(tasks)) {
            if(!tasks.isArray()||tasks.size()>2)throw invalid("A shared plan may contain at most two participant tasks.",tokens);
            for(JsonNode task:tasks) {
                if(!task.isObject())throw invalid("Each participant task must be an object.",tokens);
                validateFields(task,Set.of("resident","destination","activity","durationMinutes","action","target","item"),tokens);
                for(String field:List.of("resident","destination","activity")) {
                    validateText(task,field,field.equals("activity")?1000:80,tokens);
                    if(text(task,field)==null||text(task,field).isBlank())throw invalid("Each task needs a resident, destination and activity.",tokens);
                }
                validateMinutes(task,"durationMinutes",tokens);
                for(String field:List.of("target","item"))validateText(task,field,80,tokens);
                if(present(task.path("action"))&&(!task.path("action").isTextual()
                    ||!Set.of("DO_ACTIVITY","USE_OBJECT","READ_BOOK","RESEARCH_PROJECT","BUILD_PROJECT","BUILD_CONSTRUCTION","PRODUCE_PRODUCT").contains(text(task,"action"))))
                    throw invalid("Choose a supported executable shared task action.",tokens);
            }
        }
    }
    private static void validateFields(JsonNode node,Set<String> allowed,long tokens) {
        var fields=node.fieldNames();
        while(fields.hasNext())if(!allowed.contains(fields.next()))throw invalid("The shared agreement contains an unsupported field.",tokens);
    }
    private static void validateText(JsonNode node,String field,int limit,long tokens) {
        JsonNode value=node.path(field);
        if(present(value)&&(!value.isTextual()||value.textValue().length()>limit))throw invalid("The shared agreement contains an invalid "+field+".",tokens);
    }
    private static void validateMinutes(JsonNode node,String field,long tokens) {
        if(present(node.path(field))&&(integer(node,field)==null||integer(node,field)<1||integer(node,field)>100))
            throw invalid("Shared agreement minutes must be integers from one to one hundred.",tokens);
    }
    private static boolean present(JsonNode value) {return !value.isMissingNode()&&!value.isNull();}
    private static String text(JsonNode node,String field) {JsonNode value=node.path(field);return value.isTextual()?value.textValue():null;}
    private static Integer integer(JsonNode node,String field) {JsonNode value=node.path(field);return value.isIntegralNumber()&&value.canConvertToInt()?value.intValue():null;}
    private static Long longValue(JsonNode node,String field) {JsonNode value=node.path(field);return value.isIntegralNumber()&&value.canConvertToLong()?value.longValue():null;}
}
