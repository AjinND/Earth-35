package world.earth35.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The private local model is independent of each owner's LLM provider and credentials. */
@Component
public class Embeddings {
    public static final String MODEL="all-MiniLM-L6-v2:1110a243:mean-pool-v1";
    private final URI endpoint;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public Embeddings(@Value("${earth35.memory.embedding-url}") String url,ObjectMapper json) {endpoint=URI.create(url);this.json=json;}
    public List<List<Double>> encode(List<String> texts) {
        try {
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("texts",texts)))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException();
            var body=json.readTree(response.body());
            if(!MODEL.equals(body.path("model").asText())||body.path("embeddings").size()!=texts.size())throw new IllegalStateException();
            List<List<Double>> result=new ArrayList<>();
            for(var row:body.path("embeddings")) {
                List<Double> vector=new ArrayList<>();
                for(var number:row){if(!number.isNumber())throw new IllegalStateException();vector.add(number.asDouble());}
                if(vector.size()!=384||vector.stream().anyMatch(v->!Double.isFinite(v))||vector.stream().allMatch(v->v==0))throw new IllegalStateException();
                result.add(vector);
            }
            return result;
        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException("Local embeddings unavailable");}
        catch(Exception e) {throw new IllegalStateException("Local embeddings unavailable");}
    }
}
