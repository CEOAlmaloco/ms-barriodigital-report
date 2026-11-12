package cl.duoc.barriodigital.report.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Un error inesperado no expone su detalle. */
@RestControllerAdvice
public class ReportExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ReportExceptionHandler.class);
    static final String INTERNAL_MESSAGE = "Error interno del servicio";

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException ex, HttpServletRequest request) {
        int status = ex.getStatusCode().value();
        String message = ex.getReason() == null ? ErrorBody.reasonPhrase(status) : ex.getReason();
        return ResponseEntity.status(status).body(ErrorBody.of(status, message, request.getRequestURI()));
    }

    /** Las excepciones de Spring MVC, como una ruta inexistente, conservan su código. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse errorResponse) {
            int status = errorResponse.getStatusCode().value();
            return ResponseEntity.status(status)
                    .body(ErrorBody.of(status, ErrorBody.reasonPhrase(status), request.getRequestURI()));
        }
        log.error("Error inesperado en {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorBody.of(500, INTERNAL_MESSAGE, request.getRequestURI()));
    }
}
