package cl.duoc.barriodigital.report.messaging;

import cl.duoc.barriodigital.report.domain.ProcessedEvent;
import cl.duoc.barriodigital.report.domain.RequestEvent;
import cl.duoc.barriodigital.report.domain.RequestProjection;
import cl.duoc.barriodigital.report.domain.RequestStatus;
import cl.duoc.barriodigital.report.persistence.ProcessedEventRepository;
import cl.duoc.barriodigital.report.persistence.RequestProjectionRepository;
import cl.duoc.barriodigital.report.service.KpiService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.assigned;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.cancelled;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.citizenComment;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.created;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.newRequestId;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.rated;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.record;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.statusChanged;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class RequestEventsListenerH2Test {

    @Autowired
    private RequestEventsListener listener;

    @Autowired
    private KpiService kpiService;

    @Autowired
    private RequestProjectionRepository projections;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @BeforeEach
    void clean() {
        projections.deleteAllInBatch();
        processedEvents.deleteAllInBatch();
    }

    @Test
    void proyectaElAltaYRegistraElEvento() {
        String requestId = newRequestId();
        ObjectNode event = created(requestId, 1, "2026-11-10T13:00:00Z", 5);

        listener.onMessage(record(event));

        RequestProjection projection = projections.findById(requestId).orElseThrow();
        assertThat(projection.getStatus()).isEqualTo(RequestStatus.INGRESADO);
        assertThat(projection.getCreatedAt()).isEqualTo(Instant.parse("2026-11-10T13:00:00Z"));
        assertThat(projection.getDueDate()).isEqualTo("2026-11-17");
        assertThat(projection.getDueSoonDate()).isEqualTo("2026-11-14");
        assertThat(projection.getSlaStatus()).isNotNull();
        ProcessedEvent marker = processedEvents.findById(
                new ProcessedEvent.Key("report", event.get("eventId").asText())).orElseThrow();
        assertThat(marker.getProcessedAt()).isNotNull();
    }

    @Test
    void duplicadoSeConfirmaSinReprocesar(CapturedOutput output) {
        String requestId = newRequestId();
        listener.onMessage(record(created(requestId, 1, "2026-11-10T13:00:00Z", 5)));
        ObjectNode resolved = statusChanged(requestId, 2, "2026-11-10T14:00:00Z", "INGRESADO", "ADMITIDO");
        listener.onMessage(record(resolved));

        listener.onMessage(record(resolved));

        assertThat(processedEvents.count()).isEqualTo(2);
        RequestProjection projection = projections.findById(requestId).orElseThrow();
        assertThat(projection.getStatus()).isEqualTo(RequestStatus.ADMITIDO);
        assertThat(projection.getLastSequence()).isEqualTo(2L);
        assertThat(output).contains("duplicado topic=requests.events consumidor=report eventId="
                + resolved.get("eventId").asText());
    }

    @Test
    void malformadoNoTocaLaBase() {
        ObjectNode event = created(newRequestId(), 1, "2026-11-10T13:00:00Z", 5);
        ((ObjectNode) event.get("payload")).putNull("longitude");

        assertThatThrownBy(() -> listener.onMessage(record(event)))
                .isInstanceOf(NonRetryableEventException.class);
        assertThat(processedEvents.count()).isZero();
        assertThat(projections.count()).isZero();
    }

    @Test
    void desconocidoSeIgnoraConWarn(CapturedOutput output) {
        ObjectNode event = rated(newRequestId(), 1, "2026-11-10T13:00:00Z", 5);
        event.put("eventType", "request.reopened");

        listener.onMessage(record(event));

        assertThat(processedEvents.count()).isZero();
        assertThat(projections.count()).isZero();
        assertThat(output).contains("evento-desconocido topic=requests.events consumidor=report "
                + "eventType=request.reopened eventId=" + event.get("eventId").asText());
    }

    @Test
    void eventoAtrasadoNoCambiaElEstadoActual() {
        String requestId = newRequestId();
        listener.onMessage(record(created(requestId, 1, "2026-11-10T13:00:00Z", 5)));
        listener.onMessage(record(statusChanged(requestId, 3, "2026-11-10T15:00:00Z", "ADMITIDO", "EN_GESTION")));
        listener.onMessage(record(statusChanged(requestId, 2, "2026-11-10T14:00:00Z", "INGRESADO", "ADMITIDO")));

        RequestProjection projection = projections.findById(requestId).orElseThrow();
        assertThat(projection.getStatus()).isEqualTo(RequestStatus.EN_GESTION);
        assertThat(projection.getStatusSequence()).isEqualTo(3L);
        assertThat(processedEvents.count()).isEqualTo(3);
    }

    @Test
    void comentarioNoAlteraIndicadoresYElCambioDeEstadoPosteriorSeAplica(CapturedOutput output) {
        String withComment = newRequestId();
        String control = newRequestId();
        for (String requestId : new String[] {withComment, control}) {
            listener.onMessage(record(created(requestId, 1, "2026-11-10T13:00:00Z", 5)));
            listener.onMessage(record(assigned(requestId, 2, "2026-11-10T13:30:00Z", "func-1", "Funcionario Sur")));
        }
        listener.onMessage(record(citizenComment(withComment, 3, "2026-11-10T14:00:00Z", true)));

        RequestProjection afterComment = projections.findById(withComment).orElseThrow();
        assertThat(afterComment.getStatus()).isEqualTo(RequestStatus.INGRESADO);
        assertThat(afterComment.getStatusSequence()).isEqualTo(1L);
        assertThat(afterComment.getAssigneeSequence()).isEqualTo(2L);
        assertThat(afterComment.getLastSequence()).isEqualTo(3L);
        assertThat(afterComment.getLastEventAt()).isEqualTo(Instant.parse("2026-11-10T14:00:00Z"));

        for (String requestId : new String[] {withComment, control}) {
            listener.onMessage(record(statusChanged(requestId, 4, "2026-11-10T15:00:00Z", "INGRESADO", "ADMITIDO")));
            listener.onMessage(record(statusChanged(requestId, 5, "2026-11-10T16:00:00Z", "ADMITIDO", "RECHAZADO")));
        }

        RequestProjection commented = projections.findById(withComment).orElseThrow();
        assertThat(commented.getStatus()).isEqualTo(RequestStatus.RECHAZADO);
        assertThat(commented.getStatusSequence()).isEqualTo(5L);
        assertThat(commented.getClosedAt()).isEqualTo(Instant.parse("2026-11-10T16:00:00Z"));
        assertThat(commented.getLastSequence()).isEqualTo(5L);
        // Todo lo que leen los KPIs, el plazo y el mapa coincide con el trámite sin comentario.
        assertThat(commented).usingRecursiveComparison()
                .ignoringFields("requestId", "isNew")
                .isEqualTo(projections.findById(control).orElseThrow());
        assertThat(processedEvents.count()).isEqualTo(9);
        assertThat(output).doesNotContain("evento-desconocido");
    }

    @Test
    void comentarioAntesDelAltaCreaUnaProyeccionParcialQueElAltaCompleta() {
        String requestId = newRequestId();
        listener.onMessage(record(citizenComment(requestId, 2, "2026-11-10T14:00:00Z", false)));

        RequestProjection partial = projections.findById(requestId).orElseThrow();
        assertThat(partial.getStatus()).isNull();
        assertThat(partial.getCreatedAt()).isNull();

        listener.onMessage(record(created(requestId, 1, "2026-11-10T13:00:00Z", 5)));

        RequestProjection completed = projections.findById(requestId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(RequestStatus.INGRESADO);
        assertThat(completed.getCreatedAt()).isEqualTo(Instant.parse("2026-11-10T13:00:00Z"));
        assertThat(completed.getLastSequence()).isEqualTo(2L);
        assertThat(completed.getLastEventAt()).isEqualTo(Instant.parse("2026-11-10T14:00:00Z"));
    }

    @Test
    void cierreReprocesadoSeAplicaAunqueLlegueDespuesDeLaEncuesta() {
        String requestId = newRequestId();
        listener.onMessage(record(created(requestId, 1, "2026-11-10T13:00:00Z", 5)));
        listener.onMessage(record(statusChanged(requestId, 4, "2026-11-10T14:00:00Z", "EN_GESTION", "EN_TERRENO")));
        listener.onMessage(record(rated(requestId, 6, "2026-11-10T17:00:00Z", 5)));
        listener.onMessage(record(statusChanged(requestId, 5, "2026-11-10T16:00:00Z", "EN_TERRENO", "RESUELTO")));

        RequestProjection projection = projections.findById(requestId).orElseThrow();
        assertThat(projection.getStatus()).isEqualTo(RequestStatus.RESUELTO);
        assertThat(projection.getClosedAt()).isEqualTo(Instant.parse("2026-11-10T16:00:00Z"));
        assertThat(projection.getLastSequence()).isEqualTo(6L);
        assertThat(projection.getLastEventAt()).isEqualTo(Instant.parse("2026-11-10T17:00:00Z"));
    }

    @Test
    void parcialAbiertaCompletadaPorUnCreatedPosterior() {
        String requestId = newRequestId();
        listener.onMessage(record(assigned(requestId, 3, "2026-11-10T14:00:00Z", "func-1", "Funcionario Sur")));
        listener.onMessage(record(statusChanged(requestId, 4, "2026-11-10T15:00:00Z", "INGRESADO", "ADMITIDO")));

        RequestProjection partial = projections.findById(requestId).orElseThrow();
        assertThat(partial.isPartial()).isTrue();
        assertThat(partial.getSlaStatus()).isNull();
        assertThat(partial.getAssigneeId()).isEqualTo("func-1");

        listener.onMessage(record(created(requestId, 1, "2026-11-10T13:00:00Z", 5)));

        RequestProjection completed = projections.findById(requestId).orElseThrow();
        assertThat(completed.isPartial()).isFalse();
        assertThat(completed.getCreatedAt()).isEqualTo(Instant.parse("2026-11-10T13:00:00Z"));
        assertThat(completed.getSlaBusinessDays()).isEqualTo(5);
        assertThat(completed.getStatus()).isEqualTo(RequestStatus.ADMITIDO);
        assertThat(completed.getAssigneeId()).isEqualTo("func-1");
        assertThat(completed.getLastEventAt()).isEqualTo(Instant.parse("2026-11-10T15:00:00Z"));
    }

    @Test
    void parcialCerradaCompletadaPorUnCreatedPosterior() {
        String requestId = newRequestId();
        listener.onMessage(record(cancelled(requestId, 2, "2026-11-10T14:00:00Z")));

        listener.onMessage(record(created(requestId, 1, "2026-11-10T13:00:00Z", null)));

        RequestProjection completed = projections.findById(requestId).orElseThrow();
        assertThat(completed.isPartial()).isFalse();
        assertThat(completed.getStatus()).isEqualTo(RequestStatus.CANCELADO);
        assertThat(completed.getClosedAt()).isEqualTo(Instant.parse("2026-11-10T14:00:00Z"));
        assertThat(completed.getSlaBusinessDays()).isNull();
    }

    @Test
    void laMarcaYLaProyeccionVanEnLaMismaTransaccion() {
        // Un folio más largo que la columna hace fallar la proyección después de insertar la marca.
        RequestEvent tooLong = new RequestEvent(UUID.randomUUID(), 1, Instant.parse("2026-11-10T13:00:00Z"),
                newRequestId(), "BD-2026-0001234567", new RequestEvent.Activity());

        assertThatThrownBy(() -> kpiService.apply(tooLong))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        ex -> assertThat(DuplicateEvents.isDuplicate(ex)).isFalse());
        assertThat(processedEvents.count()).isZero();
        assertThat(projections.count()).isZero();
    }

    @Test
    void elMismoEventIdDeOtroConsumidorNoEsDuplicado() {
        ObjectNode event = created(newRequestId(), 1, "2026-11-10T13:00:00Z", 5);
        processedEvents.save(new ProcessedEvent("otro-consumidor", event.get("eventId").asText(), Instant.now()));

        listener.onMessage(record(event));

        assertThat(projections.count()).isEqualTo(1);
        assertThat(processedEvents.count()).isEqualTo(2);
    }
}
