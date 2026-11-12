package cl.duoc.barriodigital.report.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestProjectionSlaTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-10");
    private static final String REQUEST_ID = "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc";

    private final SlaCalculator calculator = new SlaCalculator(new BusinessCalendar(Set.of(), Set.of(2026)), 2);

    @Test
    void altaConPlazoCalculaFechasYEstado() {
        RequestProjection projection = project(created(1, "2026-11-09T13:00:00Z", 5));

        assertThat(projection.getDueDate()).isEqualTo("2026-11-16");
        assertThat(projection.getDueSoonDate()).isEqualTo("2026-11-13");
        assertThat(projection.getSlaStatus()).isEqualTo(SlaStatus.DENTRO_DE_PLAZO);
    }

    @Test
    void altaSinPlazoQuedaSinPlazoAbiertaYCancelada() {
        RequestProjection projection = project(created(1, "2026-11-09T13:00:00Z", null));
        assertThat(projection.getSlaStatus()).isEqualTo(SlaStatus.SIN_PLAZO);
        assertThat(projection.getDueDate()).isNull();

        apply(projection, status(2, "2026-11-10T13:00:00Z", RequestStatus.CANCELADO));
        assertThat(projection.getSlaStatus()).isEqualTo(SlaStatus.SIN_PLAZO);
    }

    @Test
    void resolverEnPlazoEsCumplidoYRechazarTardeEsFueraDePlazo() {
        RequestProjection onTime = project(created(1, "2026-11-09T13:00:00Z", 5));
        apply(onTime, status(2, "2026-11-16T20:00:00Z", RequestStatus.RESUELTO));
        assertThat(onTime.getSlaStatus()).isEqualTo(SlaStatus.CUMPLIDO);

        RequestProjection late = project(created(1, "2026-11-09T13:00:00Z", 5));
        apply(late, status(2, "2026-11-17T13:00:00Z", RequestStatus.RECHAZADO));
        assertThat(late.getSlaStatus()).isEqualTo(SlaStatus.FUERA_DE_PLAZO);
    }

    @Test
    void cancelarConPlazoNoAplica() {
        RequestProjection projection = project(created(1, "2026-11-09T13:00:00Z", 5));
        apply(projection, status(2, "2026-11-20T13:00:00Z", RequestStatus.CANCELADO));

        assertThat(projection.getSlaStatus()).isEqualTo(SlaStatus.NO_APLICA);
    }

    @Test
    void unResultadoEvaluadoNoSeRecalculaConOtroCalendario() {
        RequestProjection projection = project(created(1, "2026-11-09T13:00:00Z", 5));
        apply(projection, status(2, "2026-11-17T13:00:00Z", RequestStatus.RECHAZADO));
        assertThat(projection.getSlaStatus()).isEqualTo(SlaStatus.FUERA_DE_PLAZO);

        // Con un feriado nuevo el jueves 12 vencería el 17 y sería cumplido, pero el resultado ya informado se mantiene.
        SlaCalculator withHoliday = new SlaCalculator(
                new BusinessCalendar(Set.of(LocalDate.parse("2026-11-12")), Set.of(2026)), 2);
        projection.refreshSla(withHoliday, TODAY);

        assertThat(projection.getSlaStatus()).isEqualTo(SlaStatus.FUERA_DE_PLAZO);
        assertThat(projection.getDueDate()).isEqualTo("2026-11-16");
    }

    @Test
    void elCierreUsaLaFechaDelCalendarioCargadoAunqueLaGuardadaEsteVieja() {
        RequestProjection projection = project(created(1, "2026-11-09T13:00:00Z", 5));
        // El recálculo del arranque no corrió para este trámite y llega su cierre con un feriado nuevo el jueves 12.
        SlaCalculator withHoliday = new SlaCalculator(
                new BusinessCalendar(Set.of(LocalDate.parse("2026-11-12")), Set.of(2026)), 2);
        projection.apply(status(2, "2026-11-17T13:00:00Z", RequestStatus.RESUELTO));
        projection.refreshSla(withHoliday, TODAY);

        assertThat(projection.getDueDate()).isEqualTo("2026-11-17");
        assertThat(projection.getSlaStatus()).isEqualTo(SlaStatus.CUMPLIDO);
    }

    @Test
    void unaProyeccionParcialQuedaSinDato() {
        RequestProjection projection = project(status(3, "2026-11-10T13:00:00Z", RequestStatus.EN_GESTION));

        assertThat(projection.getSlaStatus()).isNull();
        assertThat(projection.getDueDate()).isNull();
    }

    @Test
    void unAltaTardiaCompletaLaParcialAbiertaYLaCerrada() {
        RequestProjection open = project(status(3, "2026-11-10T13:00:00Z", RequestStatus.EN_GESTION));
        apply(open, created(1, "2026-11-09T13:00:00Z", 5));
        assertThat(open.getSlaStatus()).isEqualTo(SlaStatus.DENTRO_DE_PLAZO);
        assertThat(open.getStatus()).isEqualTo(RequestStatus.EN_GESTION);

        RequestProjection closed = project(status(3, "2026-11-20T13:00:00Z", RequestStatus.RESUELTO));
        apply(closed, created(1, "2026-11-09T13:00:00Z", 5));
        assertThat(closed.getSlaStatus()).isEqualTo(SlaStatus.FUERA_DE_PLAZO);
    }

    private RequestProjection project(RequestEvent event) {
        RequestProjection projection = RequestProjection.empty(REQUEST_ID, "BD-2026-000001");
        apply(projection, event);
        return projection;
    }

    private void apply(RequestProjection projection, RequestEvent event) {
        projection.apply(event);
        projection.refreshSla(calculator, TODAY);
    }

    private static RequestEvent created(long sequence, String occurredAt, Integer slaBusinessDays) {
        return event(sequence, occurredAt,
                new RequestEvent.Created("bache", "Bache", "ralco", -38.0, -71.5, slaBusinessDays));
    }

    private static RequestEvent status(long sequence, String occurredAt, RequestStatus toStatus) {
        return event(sequence, occurredAt, new RequestEvent.StatusChanged("bache", "ralco", toStatus));
    }

    private static RequestEvent event(long sequence, String occurredAt, RequestEvent.Change change) {
        return new RequestEvent(UUID.randomUUID(), sequence, Instant.parse(occurredAt), REQUEST_ID,
                "BD-2026-000001", change);
    }
}
