package cl.duoc.barriodigital.report.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de requests.events ya validado, sin nada de Kafka.
 * change lleva solo lo que report proyecta de cada eventType.
 */
public record RequestEvent(
        UUID eventId,
        long sequence,
        Instant occurredAt,
        String requestId,
        String folio,
        Change change
) {

    public sealed interface Change permits Created, StatusChanged, Assigned, Activity {
    }

    /** slaBusinessDays es null si el tipo no tiene plazo. */
    public record Created(
            String procedureType,
            String procedureLabel,
            String sector,
            Double latitude,
            Double longitude,
            Integer slaBusinessDays
    ) implements Change {
    }

    /** request.status-changed y request.cancelled. */
    public record StatusChanged(String procedureType, String sector, RequestStatus toStatus) implements Change {
    }

    public record Assigned(String assigneeId, String assigneeName) implements Change {
    }

    /** request.contact-logged y request.rated: no cambian el estado ni el responsable. */
    public record Activity() implements Change {
    }
}
