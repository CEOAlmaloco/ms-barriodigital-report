package cl.duoc.barriodigital.report.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.UUID;

/** Mensajes de requests.events con todos los campos de su tipo, también los que van en null. */
public final class RequestEventsFixtures {

    public static final String TOPIC = "requests.events";
    public static final String DLT = "requests.events.report.DLT";
    public static final String FOLIO = "BD-2026-000123";

    private static final ObjectMapper JSON = new ObjectMapper();

    private RequestEventsFixtures() {
    }

    public static ObjectNode created(String requestId, long sequence, String occurredAt, Integer slaBusinessDays) {
        ObjectNode payload = payload(requestId);
        payload.put("procedureType", "bache");
        payload.put("procedureLabel", "Bache en calle");
        payload.put("sector", "ralco");
        payload.put("latitude", -37.9946);
        payload.put("longitude", -71.4962);
        payload.put("status", "INGRESADO");
        if (slaBusinessDays == null) {
            payload.putNull("slaBusinessDays");
        } else {
            payload.put("slaBusinessDays", slaBusinessDays);
        }
        return envelope("request.created", sequence, occurredAt, "Vecino", payload);
    }

    public static ObjectNode statusChanged(String requestId, long sequence, String occurredAt,
                                           String fromStatus, String toStatus) {
        ObjectNode payload = payload(requestId);
        payload.put("procedureType", "bache");
        payload.put("sector", "ralco");
        payload.put("fromStatus", fromStatus);
        payload.put("toStatus", toStatus);
        if ("RECHAZADO".equals(toStatus)) {
            payload.put("reason", "Direccion fuera de la comuna");
        } else {
            payload.putNull("reason");
        }
        payload.putNull("interventionReason");
        return envelope("request.status-changed", sequence, occurredAt, "Funcionario", payload);
    }

    public static ObjectNode cancelled(String requestId, long sequence, String occurredAt) {
        ObjectNode payload = payload(requestId);
        payload.put("procedureType", "bache");
        payload.put("sector", "ralco");
        payload.put("fromStatus", "INGRESADO");
        payload.put("toStatus", "CANCELADO");
        return envelope("request.cancelled", sequence, occurredAt, "Vecino", payload);
    }

    public static ObjectNode assigned(String requestId, long sequence, String occurredAt,
                                      String assigneeId, String assigneeName) {
        ObjectNode payload = payload(requestId);
        payload.put("assigneeId", assigneeId);
        payload.put("assigneeName", assigneeName);
        payload.putNull("previousAssigneeId");
        payload.putNull("reason");
        return envelope("request.assigned", sequence, occurredAt, "Funcionario", payload);
    }

    public static ObjectNode contactLogged(String requestId, long sequence, String occurredAt) {
        ObjectNode payload = payload(requestId);
        payload.put("channel", "LLAMADA");
        payload.put("result", "NO_CONTESTA");
        payload.putNull("nextAttemptAt");
        payload.putNull("interventionReason");
        return envelope("request.contact-logged", sequence, occurredAt, "Funcionario", payload);
    }

    public static ObjectNode rated(String requestId, long sequence, String occurredAt, int score) {
        ObjectNode payload = payload(requestId);
        payload.put("score", score);
        payload.putNull("comment");
        return envelope("request.rated", sequence, occurredAt, "Vecino", payload);
    }

    public static ObjectNode citizenComment(String requestId, long sequence, String occurredAt, boolean hasAttachment) {
        ObjectNode payload = payload(requestId);
        payload.put("hasAttachment", hasAttachment);
        return envelope("request.citizen-comment", sequence, occurredAt, "Vecino", payload);
    }

    public static ConsumerRecord<String, String> record(ObjectNode event) {
        String key = event.path("payload").path("requestId").asText(null);
        return new ConsumerRecord<>(TOPIC, 0, 0L, key, event.toString());
    }

    public static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(TOPIC, 0, 0L, null, value);
    }

    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    private static ObjectNode payload(String requestId) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("requestId", requestId);
        payload.put("folio", FOLIO);
        return payload;
    }

    private static ObjectNode envelope(String eventType, long sequence, String occurredAt, String role,
                                       ObjectNode payload) {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", occurredAt);
        envelope.put("producer", "requests");
        envelope.put("traceId", UUID.randomUUID().toString());
        envelope.put("sequence", sequence);
        ObjectNode actor = envelope.putObject("actor");
        actor.put("id", "5d2b8e61-3f4a-4c7b-9a10-2e3f4a5b6c7d");
        actor.put("name", role + " Test");
        actor.put("role", role);
        envelope.set("payload", payload);
        return envelope;
    }
}
