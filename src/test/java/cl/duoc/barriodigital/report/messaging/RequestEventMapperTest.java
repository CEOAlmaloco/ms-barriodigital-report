package cl.duoc.barriodigital.report.messaging;

import cl.duoc.barriodigital.report.domain.RequestEvent;
import cl.duoc.barriodigital.report.domain.RequestStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.assigned;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.cancelled;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.contactLogged;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.created;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.rated;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.statusChanged;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Usa el ObjectMapper del servicio, con su lectura estricta de números. */
@JsonTest
@Import(RequestEventMapper.class)
class RequestEventMapperTest {

    private static final String REQUEST_ID = "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc";
    private static final String AT = "2026-11-10T13:05:12.481Z";

    @Autowired
    private RequestEventMapper mapper;

    @Test
    void convierteCadaTipoConocido() {
        RequestEvent created = known(created(REQUEST_ID, 1041, AT, 5));
        assertThat(created.sequence()).isEqualTo(1041L);
        assertThat(created.occurredAt()).isEqualTo(Instant.parse(AT));
        assertThat(created.requestId()).isEqualTo(REQUEST_ID);
        assertThat(created.change()).isEqualTo(new RequestEvent.Created(
                "bache", "Bache en calle", "ralco", -37.9946, -71.4962, 5));

        assertThat(known(statusChanged(REQUEST_ID, 2, AT, "ADMITIDO", "RECHAZADO")).change())
                .isEqualTo(new RequestEvent.StatusChanged("bache", "ralco", RequestStatus.RECHAZADO));
        assertThat(known(cancelled(REQUEST_ID, 3, AT)).change())
                .isEqualTo(new RequestEvent.StatusChanged("bache", "ralco", RequestStatus.CANCELADO));
        assertThat(known(assigned(REQUEST_ID, 4, AT, "func-1", "Funcionario Sur")).change())
                .isEqualTo(new RequestEvent.Assigned("func-1", "Funcionario Sur"));
        assertThat(known(contactLogged(REQUEST_ID, 5, AT)).change()).isInstanceOf(RequestEvent.Activity.class);
        assertThat(known(rated(REQUEST_ID, 6, AT, 5)).change()).isInstanceOf(RequestEvent.Activity.class);
    }

    @Test
    void ignoraCamposDesconocidosYAceptaCualquierPrecision() {
        ObjectNode event = created(REQUEST_ID, 1, "2026-11-10T13:05:12.123456789Z", null);
        event.put("campoFuturo", "x");
        ((ObjectNode) event.get("payload")).put("otroCampo", 3);

        RequestEvent mapped = known(event);
        assertThat(mapped.occurredAt()).isEqualTo(Instant.parse("2026-11-10T13:05:12.123456789Z"));
        assertThat(((RequestEvent.Created) mapped.change()).slaBusinessDays()).isNull();
    }

    @Test
    void sinCoordenadasEsValido() {
        ObjectNode event = created(REQUEST_ID, 1, AT, 5);
        payload(event).putNull("latitude");
        payload(event).putNull("longitude");
        assertThat(((RequestEvent.Created) known(event).change()).latitude()).isNull();
    }

    @Test
    void eventTypeDesconocidoNoEsError() {
        ObjectNode event = rated(REQUEST_ID, 1, AT, 5);
        event.put("eventType", "request.reopened");

        MappedEvent mapped = mapper.map(event.toString());
        assertThat(mapped).isEqualTo(new MappedEvent.Unknown("request.reopened", event.get("eventId").asText()));
    }

    @Test
    void jsonIlegibleOVacioEsMalformado() {
        assertMalformed("{no es json", "JSON invalido");
        assertMalformed("", "JSON invalido");
        assertMalformed(null, "JSON invalido");
        assertMalformed("[1,2]", "JSON invalido");
    }

    @Test
    void sobreIncompletoEsMalformado() {
        assertMalformed(without(created(REQUEST_ID, 1, AT, 5), "eventId"), "falta eventId");
        assertMalformed(without(created(REQUEST_ID, 1, AT, 5), "eventType"), "falta eventType");
        assertMalformed(without(created(REQUEST_ID, 1, AT, 5), "occurredAt"), "falta occurredAt");
        assertMalformed(without(created(REQUEST_ID, 1, AT, 5), "traceId"), "falta traceId");
        assertMalformed(without(created(REQUEST_ID, 1, AT, 5), "sequence"), "sequence");
        assertMalformed(without(created(REQUEST_ID, 1, AT, 5), "actor"), "actor");
        assertMalformed(without(created(REQUEST_ID, 1, AT, 5), "payload"), "falta payload");

        ObjectNode otherProducer = created(REQUEST_ID, 1, AT, 5);
        otherProducer.put("producer", "catalog");
        assertMalformed(otherProducer.toString(), "producer");

        ObjectNode badRole = created(REQUEST_ID, 1, AT, 5);
        ((ObjectNode) badRole.get("actor")).put("role", "ROLE_Admin");
        assertMalformed(badRole.toString(), "actor");

        ObjectNode badUuid = created(REQUEST_ID, 1, AT, 5);
        badUuid.put("eventId", "no-es-uuid");
        assertMalformed(badUuid.toString(), "eventId");
    }

    @Test
    void tiposNoEstrictosSonMalformados() {
        ObjectNode textVersion = created(REQUEST_ID, 1, AT, 5);
        textVersion.put("schemaVersion", "1");
        assertMalformed(textVersion.toString(), "schemaVersion");

        ObjectNode decimalSequence = created(REQUEST_ID, 1, AT, 5);
        decimalSequence.put("sequence", 5.5);
        assertMalformed(decimalSequence.toString(), "sequence");

        ObjectNode textSla = created(REQUEST_ID, 1, AT, 5);
        payload(textSla).put("slaBusinessDays", "5");
        assertMalformed(textSla.toString(), "slaBusinessDays");
    }

    @Test
    void schemaVersionMayorEsIncompatible() {
        ObjectNode event = created(REQUEST_ID, 1, AT, 5);
        event.put("schemaVersion", 2);
        assertMalformed(event.toString(), "schemaVersion no soportada: 2");

        event.put("schemaVersion", 0);
        assertMalformed(event.toString(), "schemaVersion");
    }

    @Test
    void payloadInvalidoEsMalformado() {
        ObjectNode oneCoordinate = created(REQUEST_ID, 1, AT, 5);
        payload(oneCoordinate).putNull("longitude");
        assertMalformed(oneCoordinate.toString(), "latitude y longitude");

        ObjectNode notIngresado = created(REQUEST_ID, 1, AT, 5);
        payload(notIngresado).put("status", "ADMITIDO");
        assertMalformed(notIngresado.toString(), "INGRESADO");

        ObjectNode unknownStatus = statusChanged(REQUEST_ID, 2, AT, "ADMITIDO", "ARCHIVADO");
        assertMalformed(unknownStatus.toString(), "toStatus");

        ObjectNode slaOutOfRange = created(REQUEST_ID, 1, AT, 121);
        assertMalformed(slaOutOfRange.toString(), "slaBusinessDays");

        ObjectNode badFolio = created(REQUEST_ID, 1, AT, 5);
        payload(badFolio).put("folio", "BD-26-1");
        assertMalformed(badFolio.toString(), "folio");

        ObjectNode badRequestId = assigned("123", 1, AT, "func-1", "Funcionario Sur");
        assertMalformed(badRequestId.toString(), "requestId");

        ObjectNode missingType = statusChanged(REQUEST_ID, 2, AT, "ADMITIDO", "EN_GESTION");
        payload(missingType).putNull("procedureType");
        assertMalformed(missingType.toString(), "procedureType");

        ObjectNode missingAssignee = assigned(REQUEST_ID, 2, AT, "func-1", "Funcionario Sur");
        payload(missingAssignee).putNull("assigneeName");
        assertMalformed(missingAssignee.toString(), "assigneeName");

        assertMalformed(rated(REQUEST_ID, 2, AT, 6).toString(), "score");

        ObjectNode cancelledToOther = cancelled(REQUEST_ID, 2, AT);
        payload(cancelledToOther).put("toStatus", "RECHAZADO");
        assertMalformed(cancelledToOther.toString(), "toStatus");

        ObjectNode statusToCancelled = statusChanged(REQUEST_ID, 2, AT, "INGRESADO", "CANCELADO");
        assertMalformed(statusToCancelled.toString(), "toStatus");

        ObjectNode missingChannel = contactLogged(REQUEST_ID, 2, AT);
        payload(missingChannel).putNull("channel");
        assertMalformed(missingChannel.toString(), "channel");
    }

    @Test
    void leeElEventIdParaLosLogs() {
        ObjectNode event = created(REQUEST_ID, 1, AT, 5);
        assertThat(mapper.peekEventId(event.toString())).isEqualTo(event.get("eventId").asText());
        assertThat(mapper.peekEventId("{roto")).isNull();
        assertThat(mapper.peekEventId(null)).isNull();
    }

    private RequestEvent known(ObjectNode event) {
        MappedEvent mapped = mapper.map(event.toString());
        assertThat(mapped).isInstanceOf(MappedEvent.Known.class);
        return ((MappedEvent.Known) mapped).event();
    }

    private void assertMalformed(String json, String messagePart) {
        assertThatThrownBy(() -> mapper.map(json))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining(messagePart);
    }

    private static String without(ObjectNode event, String field) {
        event.remove(field);
        return event.toString();
    }

    private static ObjectNode payload(ObjectNode event) {
        return (ObjectNode) event.get("payload");
    }
}
