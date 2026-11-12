package cl.duoc.barriodigital.report.service;

import cl.duoc.barriodigital.report.domain.SlaCalculator;
import cl.duoc.barriodigital.report.domain.SlaStatus;
import cl.duoc.barriodigital.report.persistence.ProjectionRows;
import cl.duoc.barriodigital.report.persistence.RequestProjectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static cl.duoc.barriodigital.report.domain.RequestStatus.CANCELADO;
import static cl.duoc.barriodigital.report.domain.RequestStatus.EN_GESTION;
import static cl.duoc.barriodigital.report.domain.RequestStatus.RESUELTO;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SlaMaintenanceH2Test {

    /** Lunes 14-dic-2026 a las 12:00 en Chile. */
    static final Instant NOW = Instant.parse("2026-12-14T15:00:00Z");
    static final Instant CREATED = Instant.parse("2026-12-23T13:00:00Z");

    @Autowired
    RequestProjectionRepository projections;

    @Autowired
    SlaCalculator calculator;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    ProjectionRows rows;

    @BeforeEach
    void clean() {
        rows = new ProjectionRows(jdbc);
        rows.clear();
    }

    @Test
    void elRecalculoCorrigeUnaFechaCalculadaSinUnFeriado() {
        // Miércoles 23-dic con 2 días: sin el feriado del 25 vencería el viernes 25; con él, el lunes 28.
        String id = rows.created("bache", "Bache", CREATED)
                .sla(2, LocalDate.parse("2026-12-25"), LocalDate.parse("2026-12-23"), SlaStatus.DENTRO_DE_PLAZO)
                .insert();

        SlaMaintenance.Recalculation result = maintenance(NOW).recalculateOpen();

        assertThat(result).extracting("open", "updated", "failed", "complete").containsExactly(1, 1, 0, true);
        assertThat(date(id, "due_date")).isEqualTo("2026-12-28");
        assertThat(date(id, "due_soon_date")).isEqualTo("2026-12-24");
    }

    @Test
    void unVencidoVuelveADentroDePlazoCuandoElRecalculoPostergaLaFecha() {
        // Alta el martes 22 con 5 días. Con una fecha vieja (miércoles 23) estaba vencido el jueves 24; con el
        // calendario cargado vence el miércoles 30.
        Instant today = Instant.parse("2026-12-24T15:00:00Z");
        String id = rows.created("bache", "Bache", Instant.parse("2026-12-22T13:00:00Z"))
                .sla(5, LocalDate.parse("2026-12-23"), LocalDate.parse("2026-12-22"), SlaStatus.VENCIDO)
                .insert();
        SlaMaintenance maintenance = maintenance(today);

        maintenance.recalculateOpen();
        assertThat(maintenance.refreshStatuses()).isTrue();

        assertThat(date(id, "due_date")).isEqualTo("2026-12-30");
        assertThat(column(id, "sla_status")).isEqualTo("DENTRO_DE_PLAZO");
    }

    @Test
    void evaluaLasAltasProyectadasAntesDeCalcularPlazosSinTocarLasYaEvaluadas() {
        Instant closedOnTime = Instant.parse("2026-12-28T13:00:00Z");
        String withoutSla = rows.created("bache", "Bache", CREATED).insert();
        String openWithSla = rows.created("bache", "Bache", CREATED).sla(2, null, null, null).insert();
        String resolved = rows.created("bache", "Bache", CREATED).sla(2, null, null, null)
                .closed(RESUELTO, closedOnTime, null).insert();
        String cancelled = rows.created("bache", "Bache", CREATED).sla(2, null, null, null)
                .closed(CANCELADO, closedOnTime, null).insert();
        String partial = rows.partial(EN_GESTION, CREATED).insert();
        String evaluated = rows.created("bache", "Bache", CREATED)
                .sla(2, LocalDate.parse("2026-12-25"), LocalDate.parse("2026-12-23"), null)
                .closed(RESUELTO, Instant.parse("2027-01-10T13:00:00Z"), SlaStatus.FUERA_DE_PLAZO).insert();

        SlaMaintenance.PendingEvaluation result = maintenance(NOW).evaluatePending();

        assertThat(result).extracting("pending", "evaluated", "failed", "complete").containsExactly(4, 4, 0, true);
        assertThat(column(withoutSla, "sla_status")).isEqualTo("SIN_PLAZO");
        assertThat(column(openWithSla, "sla_status")).isEqualTo("DENTRO_DE_PLAZO");
        assertThat(date(openWithSla, "due_date")).isEqualTo("2026-12-28");
        assertThat(column(resolved, "sla_status")).isEqualTo("CUMPLIDO");
        assertThat(column(cancelled, "sla_status")).isEqualTo("NO_APLICA");
        assertThat(column(partial, "sla_status")).isNull();
        assertThat(column(evaluated, "sla_status")).isEqualTo("FUERA_DE_PLAZO");
        assertThat(date(evaluated, "due_date")).isEqualTo("2026-12-25");
    }

    @Test
    void elUpdateDeEstadoMueveLosTresEstadosAbiertos() {
        LocalDate today = LocalDate.parse("2026-12-14");
        String overdue = openWith(today.minusDays(1), today.minusDays(3), SlaStatus.DENTRO_DE_PLAZO);
        String dueSoon = openWith(today.plusDays(1), today, SlaStatus.DENTRO_DE_PLAZO);
        String onTime = openWith(today.plusDays(5), today.plusDays(3), SlaStatus.VENCIDO);

        assertThat(maintenance(NOW).refreshStatuses()).isTrue();

        assertThat(column(overdue, "sla_status")).isEqualTo("VENCIDO");
        assertThat(column(dueSoon, "sla_status")).isEqualTo("POR_VENCER");
        assertThat(column(onTime, "sla_status")).isEqualTo("DENTRO_DE_PLAZO");
    }

    @Test
    void niElRecalculoNiElUpdateTocanSinPlazoParcialesNiCerrados() {
        String withoutSla = rows.created("bache", "Bache", CREATED).sla(null, null, null, SlaStatus.SIN_PLAZO).insert();
        String partial = rows.partial(EN_GESTION, CREATED).insert();
        String closed = rows.created("bache", "Bache", CREATED)
                .sla(2, LocalDate.parse("2026-12-25"), LocalDate.parse("2026-12-24"), null)
                .closed(RESUELTO, Instant.parse("2026-12-24T13:00:00Z"), SlaStatus.CUMPLIDO)
                .insert();
        SlaMaintenance maintenance = maintenance(Instant.parse("2027-02-01T15:00:00Z"));

        assertThat(maintenance.recalculateOpen().open()).isZero();
        maintenance.refreshStatuses();

        assertThat(column(withoutSla, "sla_status")).isEqualTo("SIN_PLAZO");
        assertThat(column(withoutSla, "due_date")).isNull();
        assertThat(column(partial, "sla_status")).isNull();
        assertThat(column(closed, "sla_status")).isEqualTo("CUMPLIDO");
        assertThat(date(closed, "due_date")).isEqualTo("2026-12-25");
    }

    @Test
    void unCierreQueTieneLaFilaBloqueadaNoEsSobrescritoPorElRecalculo() throws Exception {
        String id = rows.created("bache", "Bache", CREATED)
                .sla(2, LocalDate.parse("2026-12-25"), LocalDate.parse("2026-12-24"), SlaStatus.DENTRO_DE_PLAZO)
                .insert();
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> listener = pool.submit(() -> transactions.executeWithoutResult(status -> {
                projections.findByIdForUpdate(id).orElseThrow();
                locked.countDown();
                sleep(400);
                // Como el listener: cierra el trámite con la fecha que leyó bajo el bloqueo.
                jdbc.update("update report_request_projection set closed_at = ?, status = 'RESUELTO',"
                                + " sla_status = 'CUMPLIDO' where request_id = ?",
                        Instant.parse("2026-12-24T13:00:00Z").atOffset(ZoneOffset.UTC), id);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            SlaMaintenance.Recalculation result = pool.submit(() -> maintenance(NOW).recalculateOpen())
                    .get(10, TimeUnit.SECONDS);
            listener.get(10, TimeUnit.SECONDS);

            // Lo leyó abierto, esperó el bloqueo y, ya cerrado, el WHERE lo dejó fuera.
            assertThat(result.open()).isEqualTo(1);
            assertThat(result.updated()).isZero();
            assertThat(date(id, "due_date")).isEqualTo("2026-12-25");
            assertThat(column(id, "sla_status")).isEqualTo("CUMPLIDO");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void dosInstanciasALaVezDejanElMismoResultadoSinTocarOtrasColumnas() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(rows.created("bache", "Bache " + i, CREATED.plusSeconds(i * 60L))
                    .sla(2, LocalDate.parse("2026-12-25"), LocalDate.parse("2026-12-24"), SlaStatus.VENCIDO)
                    .assignee("func-" + i, "Funcionario " + i)
                    .status(EN_GESTION)
                    .insert());
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> runs = new ArrayList<>();
            for (int instance = 0; instance < 2; instance++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    SlaMaintenance maintenance = maintenance(NOW);
                    return maintenance.recalculateOpen().complete() & maintenance.refreshStatuses();
                }));
            }
            start.countDown();
            for (Future<Boolean> run : runs) {
                assertThat(run.get(20, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        for (int i = 0; i < ids.size(); i++) {
            Map<String, Object> row = jdbc.queryForMap("select * from report_request_projection where request_id = ?",
                    ids.get(i));
            assertThat(date(ids.get(i), "due_date")).isEqualTo("2026-12-28");
            assertThat(row.get("SLA_STATUS")).isEqualTo("DENTRO_DE_PLAZO");
            assertThat(row.get("STATUS")).isEqualTo("EN_GESTION");
            assertThat(row.get("ASSIGNEE_ID")).isEqualTo("func-" + i);
            assertThat(row.get("PROCEDURE_LABEL")).isEqualTo("Bache " + i);
        }
    }

    private String openWith(LocalDate due, LocalDate dueSoon, SlaStatus current) {
        return rows.created("bache", "Bache", CREATED).sla(2, due, dueSoon, current).insert();
    }

    private LocalDate date(String requestId, String column) {
        return jdbc.queryForObject("select " + column + " from report_request_projection where request_id = ?",
                LocalDate.class, requestId);
    }

    private Object column(String requestId, String column) {
        return jdbc.queryForObject("select " + column + " from report_request_projection where request_id = ?",
                Object.class, requestId);
    }

    private SlaMaintenance maintenance(Instant now) {
        return new SlaMaintenance(projections, calculator, transactions, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
