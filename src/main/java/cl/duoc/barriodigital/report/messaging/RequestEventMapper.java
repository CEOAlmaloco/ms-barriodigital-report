package cl.duoc.barriodigital.report.messaging;

import cl.duoc.barriodigital.report.domain.RequestEvent;
import cl.duoc.barriodigital.report.domain.RequestStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Valida un mensaje de requests.events y lo convierte al evento de dominio.
 * Un mensaje malformado o con schemaVersion no soportada lanza NonRetryableEventException.
 */
@Component
public class RequestEventMapper {

    static final String CREATED = "request.created";
    static final String STATUS_CHANGED = "request.status-changed";
    static final String CANCELLED = "request.cancelled";
    static final String CONTACT_LOGGED = "request.contact-logged";
    static final String ASSIGNED = "request.assigned";
    static final String RATED = "request.rated";
    static final String CITIZEN_COMMENT = "request.citizen-comment";

    /** Una versión mayor es incompatible: va a la DLT hasta que report la soporte. */
    static final Map<String, Set<Integer>> SUPPORTED_VERSIONS = Map.of(
            CREATED, Set.of(1),
            STATUS_CHANGED, Set.of(1),
            CANCELLED, Set.of(1),
            CONTACT_LOGGED, Set.of(1),
            ASSIGNED, Set.of(1),
            RATED, Set.of(1),
            CITIZEN_COMMENT, Set.of(1));

    private static final String PRODUCER = "requests";
    private static final Set<String> ROLES = Set.of("Admin", "Funcionario", "Vecino", "Auditor");
    private static final Pattern FOLIO = Pattern.compile("BD-\\d{4}-\\d{6}");
    private static final int MAX_SLA_BUSINESS_DAYS = 120;

    private final ObjectMapper objectMapper;

    public RequestEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    MappedEvent map(String json) {
        JsonNode tree = readTree(json);
        String eventType = text(tree, "eventType");
        if (!StringUtils.hasText(eventType)) {
            throw new NonRetryableEventException("Sobre invalido: falta eventType");
        }
        Set<Integer> versions = SUPPORTED_VERSIONS.get(eventType);
        if (versions == null) {
            String eventId = text(tree, "eventId");
            return new MappedEvent.Unknown(eventType, eventId == null ? "desconocido" : eventId);
        }
        RequestEventEnvelope envelope = convert(tree, RequestEventEnvelope.class, "Sobre invalido");
        validateEnvelope(envelope, versions);
        return new MappedEvent.Known(toEvent(envelope));
    }

    /** eventId del mensaje, o null si no se puede leer. Solo para los logs. */
    String peekEventId(String json) {
        try {
            return text(objectMapper.readTree(json), "eventId");
        } catch (Exception ex) {
            return null;
        }
    }

    private void validateEnvelope(RequestEventEnvelope envelope, Set<Integer> versions) {
        require(envelope.eventId() != null, "Sobre invalido: falta eventId");
        require(envelope.schemaVersion() != null && envelope.schemaVersion() >= 1,
                "Sobre invalido: schemaVersion");
        if (!versions.contains(envelope.schemaVersion())) {
            throw new NonRetryableEventException("schemaVersion no soportada: " + envelope.schemaVersion());
        }
        require(envelope.occurredAt() != null, "Sobre invalido: falta occurredAt");
        require(PRODUCER.equals(envelope.producer()), "Sobre invalido: producer");
        require(envelope.traceId() != null, "Sobre invalido: falta traceId");
        require(envelope.sequence() != null && envelope.sequence() >= 1, "Sobre invalido: sequence");
        RequestEventEnvelope.Actor actor = envelope.actor();
        require(actor != null && StringUtils.hasText(actor.id()) && StringUtils.hasText(actor.name())
                && ROLES.contains(actor.role()), "Sobre invalido: actor");
        require(envelope.payload() != null && envelope.payload().isObject(), "Sobre invalido: falta payload");
    }

    private RequestEvent toEvent(RequestEventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        return switch (envelope.eventType()) {
            case CREATED -> created(envelope, convert(payload, RequestEventEnvelope.Created.class));
            case STATUS_CHANGED, CANCELLED ->
                    statusChange(envelope, convert(payload, RequestEventEnvelope.StatusChange.class));
            case ASSIGNED -> assigned(envelope, convert(payload, RequestEventEnvelope.Assigned.class));
            case CONTACT_LOGGED -> contactLogged(envelope, convert(payload, RequestEventEnvelope.ContactLogged.class));
            case RATED -> rated(envelope, convert(payload, RequestEventEnvelope.Rated.class));
            case CITIZEN_COMMENT ->
                    citizenComment(envelope, convert(payload, RequestEventEnvelope.CitizenComment.class));
            default -> throw new IllegalStateException("eventType sin conversion: " + envelope.eventType());
        };
    }

    private RequestEvent created(RequestEventEnvelope envelope, RequestEventEnvelope.Created p) {
        requireIdentity(p.requestId(), p.folio());
        requireText(p.procedureType(), "procedureType");
        requireText(p.procedureLabel(), "procedureLabel");
        require((p.latitude() == null) == (p.longitude() == null),
                "Payload invalido: latitude y longitude van juntos");
        require(p.status() == RequestStatus.INGRESADO, "Payload invalido: status debe ser INGRESADO");
        require(p.slaBusinessDays() == null
                        || (p.slaBusinessDays() >= 1 && p.slaBusinessDays() <= MAX_SLA_BUSINESS_DAYS),
                "Payload invalido: slaBusinessDays");
        return event(envelope, p.requestId(), p.folio(), new RequestEvent.Created(
                p.procedureType(), p.procedureLabel(), p.sector(), p.latitude(), p.longitude(),
                p.slaBusinessDays()));
    }

    private RequestEvent statusChange(RequestEventEnvelope envelope, RequestEventEnvelope.StatusChange p) {
        requireIdentity(p.requestId(), p.folio());
        requireText(p.procedureType(), "procedureType");
        require(p.fromStatus() != null && p.toStatus() != null, "Payload invalido: fromStatus y toStatus");
        boolean cancelled = CANCELLED.equals(envelope.eventType());
        require(cancelled == (p.toStatus() == RequestStatus.CANCELADO),
                "Payload invalido: toStatus no corresponde a " + envelope.eventType());
        return event(envelope, p.requestId(), p.folio(),
                new RequestEvent.StatusChanged(p.procedureType(), p.sector(), p.toStatus()));
    }

    private RequestEvent assigned(RequestEventEnvelope envelope, RequestEventEnvelope.Assigned p) {
        requireIdentity(p.requestId(), p.folio());
        requireText(p.assigneeId(), "assigneeId");
        requireText(p.assigneeName(), "assigneeName");
        return event(envelope, p.requestId(), p.folio(), new RequestEvent.Assigned(p.assigneeId(), p.assigneeName()));
    }

    private RequestEvent contactLogged(RequestEventEnvelope envelope, RequestEventEnvelope.ContactLogged p) {
        requireIdentity(p.requestId(), p.folio());
        requireText(p.channel(), "channel");
        requireText(p.result(), "result");
        return event(envelope, p.requestId(), p.folio(), new RequestEvent.Activity());
    }

    private RequestEvent rated(RequestEventEnvelope envelope, RequestEventEnvelope.Rated p) {
        requireIdentity(p.requestId(), p.folio());
        require(p.score() != null && p.score() >= 1 && p.score() <= 5, "Payload invalido: score");
        return event(envelope, p.requestId(), p.folio(), new RequestEvent.Activity());
    }

    private RequestEvent citizenComment(RequestEventEnvelope envelope, RequestEventEnvelope.CitizenComment p) {
        requireIdentity(p.requestId(), p.folio());
        require(p.hasAttachment() != null, "Payload invalido: falta hasAttachment");
        return event(envelope, p.requestId(), p.folio(), new RequestEvent.Activity());
    }

    private static RequestEvent event(RequestEventEnvelope envelope, String requestId, String folio,
                                      RequestEvent.Change change) {
        return new RequestEvent(envelope.eventId(), envelope.sequence(), envelope.occurredAt(),
                requestId, folio, change);
    }

    private static void requireIdentity(String requestId, String folio) {
        require(isUuid(requestId), "Payload invalido: requestId");
        require(folio != null && FOLIO.matcher(folio).matches(), "Payload invalido: folio");
    }

    private static void requireText(String value, String field) {
        require(StringUtils.hasText(value), "Payload invalido: falta " + field);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new NonRetryableEventException(message);
        }
    }

    private static boolean isUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private JsonNode readTree(String json) {
        if (!StringUtils.hasText(json)) {
            throw new NonRetryableEventException("JSON invalido: cuerpo vacio");
        }
        try {
            JsonNode tree = objectMapper.readTree(json);
            if (tree == null || !tree.isObject()) {
                throw new NonRetryableEventException("JSON invalido: no es un objeto");
            }
            return tree;
        } catch (JsonProcessingException ex) {
            throw new NonRetryableEventException("JSON invalido", ex);
        }
    }

    private <T> T convert(JsonNode node, Class<T> type) {
        return convert(node, type, "Payload invalido");
    }

    private <T> T convert(JsonNode node, Class<T> type, String message) {
        try {
            return objectMapper.treeToValue(node, type);
        } catch (JsonMappingException ex) {
            throw new NonRetryableEventException(message + ": " + fieldPath(ex), ex);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new NonRetryableEventException(message, ex);
        }
    }

    private static String fieldPath(JsonMappingException ex) {
        String path = ex.getPath().stream()
                .map(reference -> reference.getFieldName() != null
                        ? reference.getFieldName() : String.valueOf(reference.getIndex()))
                .collect(Collectors.joining("."));
        return path.isEmpty() ? "formato" : path;
    }

    private static String text(JsonNode tree, String field) {
        JsonNode node = tree.get(field);
        return node == null || node.isNull() || !node.isTextual() ? null : node.asText();
    }
}
