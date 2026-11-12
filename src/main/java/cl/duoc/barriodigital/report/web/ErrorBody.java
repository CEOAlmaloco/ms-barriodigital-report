package cl.duoc.barriodigital.report.web;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mismo cuerpo de error que el BFF. */
public final class ErrorBody {

    private ErrorBody() {
    }

    public static Map<String, Object> of(int status, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", reasonPhrase(status));
        body.put("message", message);
        body.put("path", path);
        return body;
    }

    static String reasonPhrase(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved == null ? "Error" : resolved.getReasonPhrase();
    }
}
