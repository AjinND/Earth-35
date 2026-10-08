package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.Application;

class LoanTermSchemaTest {
    @Test void generatedCommandContractUsesNumericTermsMatchingTheAcceptedWirePayload() {
        var api=new OpenAPI().components(new Components().schemas(ModelConverters.getInstance().read(Contracts.Command.class)));
        new Application().numericLoanTermSchema().customise(api);
        var command=new ObjectMapper().valueToTree(api).path("components").path("schemas").path("Command");
        var terms=command.path("properties").path("loanYears");
        assertEquals("integer",terms.path("type").asText());
        assertEquals(2,terms.path("enum").size());
        assertTrue(terms.path("enum").path(0).isIntegralNumber());
        assertTrue(terms.path("enum").path(1).isIntegralNumber());
        assertEquals(3,terms.path("enum").path(0).asInt());
        assertEquals(5,terms.path("enum").path(1).asInt());
        assertEquals(3,terms.path("minimum").asInt());assertEquals(5,terms.path("maximum").asInt());
        assertFalse(command.path("required").toString().contains("loanYears"));
        assertFalse(command.path("properties").has("loanTermValid"));
        assertEquals("paid",command.path("properties").path("laborMode").path("enum").path(0).asText());
    }
}
