package world.earth35.protocol;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import world.earth35.world.Engine;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> status(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",e.getReason()==null?"Request failed":e.getReason(),"status",e.getStatusCode().value())); }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,IllegalArgumentException.class}) ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("error","Invalid request. Check the protocol schema and field limits.","status",400)); }
    @ExceptionHandler(Engine.Invalid.class) ResponseEntity<?> rule(Engine.Invalid e) { return ResponseEntity.unprocessableEntity().body(Map.of("error",e.getMessage(),"status",422)); }
}
