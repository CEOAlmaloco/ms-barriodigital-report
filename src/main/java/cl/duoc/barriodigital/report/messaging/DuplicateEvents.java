package cl.duoc.barriodigital.report.messaging;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.regex.Pattern;

/**
 * Reconoce un duplicado solo por la violación de pk_processed_events.
 * Cualquier otra violación, aunque sea de unicidad, se propaga a los reintentos.
 */
final class DuplicateEvents {

    /** Nombre exacto: Oracle lo informa como ESQUEMA.PK_PROCESSED_EVENTS y H2 le suma el detalle del índice. */
    private static final Pattern CONSTRAINT =
            Pattern.compile("(?<![A-Z0-9_$#])PK_PROCESSED_EVENTS(?![A-Z0-9_$#])", Pattern.CASE_INSENSITIVE);

    private DuplicateEvents() {
    }

    static boolean isDuplicate(DataIntegrityViolationException ex) {
        for (Throwable current = ex; current != null; current = next(current)) {
            if (current instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return CONSTRAINT.matcher(violation.getConstraintName()).find();
            }
        }
        // Respaldo si el driver no informa el nombre: lo busca en los mensajes.
        for (Throwable current = ex; current != null; current = next(current)) {
            if (current.getMessage() != null && CONSTRAINT.matcher(current.getMessage()).find()) {
                return true;
            }
        }
        return false;
    }

    private static Throwable next(Throwable current) {
        Throwable cause = current.getCause();
        return cause == current ? null : cause;
    }
}
