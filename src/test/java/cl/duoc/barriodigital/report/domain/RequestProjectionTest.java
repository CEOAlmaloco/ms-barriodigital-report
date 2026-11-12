package cl.duoc.barriodigital.report.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestProjectionTest {

    private static final String REQUEST_ID = "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc";
    private static final String FOLIO = "BD-2026-000123";
    private static final Instant T1 = Instant.parse("2026-11-10T13:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-10T14:00:00Z");
    private static final Instant T3 = Instant.parse("2026-11-10T15:00:00Z");
    private static final Instant T4 = Instant.parse("2026-11-10T16:00:00Z");

    @Test
    void altaCompletaCuentaDesdeElInicio() {
        RequestProjection projection = RequestProjection.empty(REQUEST_ID, FOLIO);
        projection.apply(created(1, T1, 5));

        assertThat(projection.isPartial()).isFalse();
        assertThat(projection.getCreatedAt()).isEqualTo(T1);
        assertThat(projection.getStatus()).isEqualTo(RequestStatus.INGRESADO);
        assertThat(projection.getStatusSequence()).isEqualTo(1L);
        assertThat(projection.getProcedureType()).isEqualTo("bache");
        assertThat(projection.getProcedureLabel()).isEqualTo("Bache en calle");
        assertThat(projection.getSector()).isEqualTo("ralco");
        assertThat(projection.getLatitude()).isEqualTo(-37.9946);
        assertThat(projection.getLongitude()).isEqualTo(-71.4962);
        assertThat(projection.getSlaBusinessDays()).isEqualTo(5);
        assertThat(projection.getSlaStatus()).isNull();
        assertThat(projection.getClosedAt()).isNull();
        assertThat(projection.getLastSequence()).isEqualTo(1L);
        assertThat(projection.getLastEventAt()).isEqualTo(T1);
    }

    @Test
    void estadoAtrasadoNoCambiaElEstadoActual() {
        RequestProjection projection = RequestProjection.empty(REQUEST_ID, FOLIO);
        projection.apply(created(1, T1, 5));
        projection.apply(status(3, T3, RequestStatus.EN_GESTION));
        projection.apply(status(2, T2, RequestStatus.ADMITIDO));

        assertThat(projection.getStatus()).isEqualTo(RequestStatus.EN_GESTION);
        assertThat(projection.getStatusSequence()).isEqualTo(3L);
        assertThat(projection.getLastSequence()).isEqualTo(3L);
        assertThat(projection.getLastEventAt()).isEqualTo(T3);
    }

    @Test
    void eventoDeOtroAspectoNoBloqueaUnEstadoReprocesado() {
        // El RESUELTO (5) fue a la DLT y la encuesta (6) llegó antes del replay.
        RequestProjection projection = RequestProjection.empty(REQUEST_ID, FOLIO);
        projection.apply(created(1, T1, 5));
        projection.apply(status(4, T2, RequestStatus.EN_TERRENO));
        projection.apply(activity(6, T4));
        projection.apply(status(5, T3, RequestStatus.RESUELTO));

        assertThat(projection.getStatus()).isEqualTo(RequestStatus.RESUELTO);
        assertThat(projection.getStatusSequence()).isEqualTo(5L);
        assertThat(projection.getClosedAt()).isEqualTo(T3);
        assertThat(projection.getLastSequence()).isEqualTo(6L);
        assertThat(projection.getLastEventAt()).isEqualTo(T4);
    }

    @Test
    void asignacionPosteriorNoBloqueaUnEstadoReprocesado() {
        RequestProjection projection = RequestProjection.empty(REQUEST_ID, FOLIO);
        projection.apply(created(1, T1, 5));
        projection.apply(assigned(3, T3, "func-2", "Funcionaria Norte"));
        projection.apply(status(2, T2, RequestStatus.ADMITIDO));

        assertThat(projection.getStatus()).isEqualTo(RequestStatus.ADMITIDO);
        assertThat(projection.getAssigneeId()).isEqualTo("func-2");
    }

    @Test
    void asignacionAtrasadaNoCambiaElResponsable() {
        RequestProjection projection = RequestProjection.empty(REQUEST_ID, FOLIO);
        projection.apply(created(1, T1, 5));
        projection.apply(assigned(4, T3, "func-2", "Funcionaria Norte"));
        projection.apply(assigned(2, T2, "func-1", "Funcionario Sur"));

        assertThat(projection.getAssigneeId()).isEqualTo("func-2");
        assertThat(projection.getAssigneeName()).isEqualTo("Funcionaria Norte");
        assertThat(projection.getAssigneeSequence()).isEqualTo(4L);
    }

    @Test
    void estadoPosteriorNoBloqueaUnaAsignacionReprocesada() {
        RequestProjection projection = RequestProjection.empty(REQUEST_ID, FOLIO);
        projection.apply(created(1, T1, 5));
        projection.apply(status(3, T3, RequestStatus.EN_GESTION));
        projection.apply(assigned(2, T2, "func-1", "Funcionario Sur"));

        assertThat(projection.getAssigneeId()).isEqualTo("func-1");
        assertThat(projection.getStatus()).isEqualTo(RequestStatus.EN_GESTION);
    }

    @Test
    void eventoSinProyeccionCreaUnaParcial() {
        RequestProjection projection = RequestProjection.empty(REQUEST_ID, FOLIO);
        projection.apply(status(7, T2, RequestStatus.EN_GESTION));

        assertThat(projection.isPartial()).isTrue();
        assertThat(projection.getCreatedAt()).isNull();
        assertThat(projection.getSlaStatus()).isNull();
        assertThat(projection.getStatus()).isEqualTo(RequestStatus.EN_GESTION);
        assertThat(projection.getFolio()).isEqualTo(FOLIO);
        assertThat(projection.getProcedureType()).isEqualTo("bache");
        assertThat(projection.getLastSequence()).isEqualTo(7L);
    }

    @Test
    void createdAtrasadoCompletaUnaParcialAbiertaSinCambiarSuEstado() {
        RequestProjection projection = RequestProjection.empty(REQUEST_ID, FOLIO);
        projection.apply(assigned(3, T2, "func-1", "Funcionario Sur"));
        projection.apply(status(4, T3, RequestStatus.ADMITIDO));
        projection.apply(created(1, T1, 5));

        assertThat(projection.isPartial()).isFalse();
        assertThat(projection.getCreatedAt()).isEqualTo(T1);
        assertThat(projection.getSlaBusinessDays()).isEqualTo(5);
        assertThat(projection.getProcedureLabel()).isEqualTo("Bache en calle");
        assertThat(projection.getStatus()).isEqualTo(RequestStatus.ADMITIDO);
        assertThat(projection.getStatusSequence()).isEqualTo(4L);
        assertThat(projection.getAssigneeId()).isEqualTo("func-1");
        assertThat(projection.getLastSequence()).isEqualTo(4L);
        assertThat(projection.getLastEventAt()).isEqualTo(T3);
    }

    @Test
    void createdAtrasadoDeUnaParcialSinEstadoDejaIngresado() {
        RequestProjection projection = RequestProjection.empty(REQUEST_ID, FOLIO);
        projection.apply(assigned(3, T2, "func-1", "Funcionario Sur"));
        projection.apply(created(1, T1, null));

        assertThat(projection.getStatus()).isEqualTo(RequestStatus.INGRESADO);
        assertThat(projection.getStatusSequence()).isEqualTo(1L);
        assertThat(projection.getSlaBusinessDays()).isNull();
    }

    @Test
    void createdAtrasadoCompletaUnaParcialCerradaConservandoElCierre() {
        RequestProjection projection = RequestProjection.empty(REQUEST_ID, FOLIO);
        projection.apply(status(6, T3, RequestStatus.RECHAZADO));
        projection.apply(created(1, T1, 5));

        assertThat(projection.isPartial()).isFalse();
        assertThat(projection.getCreatedAt()).isEqualTo(T1);
        assertThat(projection.getStatus()).isEqualTo(RequestStatus.RECHAZADO);
        assertThat(projection.getClosedAt()).isEqualTo(T3);
    }

    @Test
    void createdRepetidoConOtroEventIdNoReescribeElAlta() {
        RequestProjection projection = RequestProjection.empty(REQUEST_ID, FOLIO);
        projection.apply(created(1, T1, 5));
        projection.apply(created(1, T2, 9));

        assertThat(projection.getCreatedAt()).isEqualTo(T1);
        assertThat(projection.getSlaBusinessDays()).isEqualTo(5);
    }

    @Test
    void cancelacionCierraElTramite() {
        RequestProjection projection = RequestProjection.empty(REQUEST_ID, FOLIO);
        projection.apply(created(1, T1, 5));
        projection.apply(status(2, T2, RequestStatus.CANCELADO));

        assertThat(projection.getStatus()).isEqualTo(RequestStatus.CANCELADO);
        assertThat(projection.getClosedAt()).isEqualTo(T2);
    }

    @Test
    void lasGestionesYEncuestasSoloMuevenElUltimoEvento() {
        RequestProjection projection = RequestProjection.empty(REQUEST_ID, FOLIO);
        projection.apply(created(1, T1, 5));
        projection.apply(activity(2, T2));

        assertThat(projection.getStatus()).isEqualTo(RequestStatus.INGRESADO);
        assertThat(projection.getStatusSequence()).isEqualTo(1L);
        assertThat(projection.getLastSequence()).isEqualTo(2L);
        assertThat(projection.getLastEventAt()).isEqualTo(T2);
    }

    @Test
    void unEventoAtrasadoNoRetrocedeElUltimoEvento() {
        RequestProjection projection = RequestProjection.empty(REQUEST_ID, FOLIO);
        projection.apply(activity(5, T3));
        projection.apply(created(1, T1, 5));

        assertThat(projection.getLastEventAt()).isEqualTo(T3);
        assertThat(projection.getLastSequence()).isEqualTo(5L);
    }

    private static RequestEvent created(long sequence, Instant at, Integer sla) {
        return event(sequence, at, new RequestEvent.Created("bache", "Bache en calle", "ralco",
                -37.9946, -71.4962, sla));
    }

    private static RequestEvent status(long sequence, Instant at, RequestStatus to) {
        return event(sequence, at, new RequestEvent.StatusChanged("bache", "ralco", to));
    }

    private static RequestEvent assigned(long sequence, Instant at, String id, String name) {
        return event(sequence, at, new RequestEvent.Assigned(id, name));
    }

    private static RequestEvent activity(long sequence, Instant at) {
        return event(sequence, at, new RequestEvent.Activity());
    }

    private static RequestEvent event(long sequence, Instant at, RequestEvent.Change change) {
        return new RequestEvent(UUID.randomUUID(), sequence, at, REQUEST_ID, FOLIO, change);
    }
}
