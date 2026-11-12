package cl.duoc.barriodigital.report.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Vuelve a comprobar el rol del BFF: sin rol, con más de uno o no permitido, el mismo 403. */
public final class ReportCaller {

    public static final String HEADER_USER_ROLES = "X-User-Roles";
    public static final String FORBIDDEN_MESSAGE = "No tienes el rol necesario para este recurso";

    public static final String VECINO = "Vecino";
    public static final String FUNCIONARIO = "Funcionario";
    public static final String ADMIN = "Admin";
    public static final String AUDITOR = "Auditor";
    private static final List<String> ROLES = List.of(VECINO, FUNCIONARIO, ADMIN, AUDITOR);
    private static final String ROLE_PREFIX = "ROLE_";

    private ReportCaller() {
    }

    public static void requireRole(String rolesHeader, Set<String> allowed) {
        List<String> roles = roles(rolesHeader);
        if (roles.size() != 1 || !allowed.contains(roles.get(0))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, FORBIDDEN_MESSAGE);
        }
    }

    /** Mismo criterio que requests: sin prefijo ROLE_, sin distinguir mayúsculas, y lo que no es un rol no cuenta. */
    static List<String> roles(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (String part : rolesHeader.split(",")) {
            String value = part.trim();
            if (value.regionMatches(true, 0, ROLE_PREFIX, 0, ROLE_PREFIX.length())) {
                value = value.substring(ROLE_PREFIX.length());
            }
            for (String role : ROLES) {
                if (role.equalsIgnoreCase(value) && !found.contains(role)) {
                    found.add(role);
                }
            }
        }
        return found;
    }
}
