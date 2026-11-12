package cl.duoc.barriodigital.report.messaging;

import cl.duoc.barriodigital.report.domain.RequestStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Campos de requests.events que report lee. Los desconocidos se ignoran. */
record RequestEventEnvelope(
        UUID eventId,
        String eventType,
        Integer schemaVersion,
        Instant occurredAt,
        String producer,
        UUID traceId,
        Long sequence,
        Actor actor,
        JsonNode payload
) {

    record Actor(String id, String name, String role) {
    }

    record Created(
            String requestId,
            String folio,
            String procedureType,
            String procedureLabel,
            String sector,
            Double latitude,
            Double longitude,
            RequestStatus status,
            Integer slaBusinessDays
    ) {
    }

    /** request.status-changed y request.cancelled. */
    record StatusChange(
            String requestId,
            String folio,
            String procedureType,
            String sector,
            RequestStatus fromStatus,
            RequestStatus toStatus
    ) {
    }

    record ContactLogged(String requestId, String folio, String channel, String result) {
    }

    record Assigned(String requestId, String folio, String assigneeId, String assigneeName) {
    }

    record Rated(String requestId, String folio, Integer score) {
    }
}
