package world.earth35;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;

@SpringBootApplication
@EnableScheduling
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }

    @Bean public OpenApiCustomizer numericLoanTermSchema() {
        return api->{
            if(api.getComponents()==null||api.getComponents().getSchemas()==null)return;
            Schema<?> command=api.getComponents().getSchemas().get("Command");
            if(command==null||command.getProperties()==null)return;
            Schema<?> original=command.getProperties().get("loanYears");
            if(original==null)return;
            // Annotation allowableValues are strings; the wire command requires integers.
            var term=new IntegerSchema();term.setEnum(List.of(3,5));
            term.setDescription(original.getDescription());
            term.setMinimum(original.getMinimum());term.setMaximum(original.getMaximum());
            command.addProperty("loanYears",term);
        };
    }
}
