package cl.duoc.barriodigital.report.service;

import cl.duoc.barriodigital.report.domain.BusinessCalendar;
import cl.duoc.barriodigital.report.domain.SlaCalculator;
import cl.duoc.barriodigital.report.persistence.RequestProjectionRepository;
import cl.duoc.barriodigital.report.persistence.SlaSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlaMaintenanceTest {

    private final RequestProjectionRepository projections = mock(RequestProjectionRepository.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final SlaMaintenance maintenance = new SlaMaintenance(projections,
            new SlaCalculator(new BusinessCalendar(Set.of(), Set.of(2026)), 2),
            new TransactionTemplate(txManager), Clock.fixed(Instant.parse("2026-12-14T15:00:00Z"), ZoneOffset.UTC));

    SlaMaintenanceTest() {
        when(txManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
    }

    @Test
    void reintentaUnaVezUnDeadlockEnUnaTransaccionNueva() {
        when(projections.refreshOpenSlaStatuses(eq(LocalDate.parse("2026-12-14")), any(), any(), any()))
                .thenThrow(new PessimisticLockingFailureException("ORA-00060"))
                .thenReturn(3);

        assertThat(maintenance.refreshStatuses()).isTrue();

        verify(txManager, times(2)).getTransaction(any());
        verify(txManager, times(1)).rollback(any(TransactionStatus.class));
        verify(txManager, times(1)).commit(any(TransactionStatus.class));
        assertThat(maintenance.lastStatusRefresh().ok()).isTrue();
    }

    @Test
    void siVuelveAFallarNoInsisteYLoDejaRegistrado() {
        when(projections.refreshOpenSlaStatuses(any(), any(), any(), any()))
                .thenThrow(new CannotAcquireLockException("ORA-00060"));

        assertThat(maintenance.refreshStatuses()).isFalse();

        verify(projections, times(SlaMaintenance.STATUS_ATTEMPTS)).refreshOpenSlaStatuses(any(), any(), any(), any());
        assertThat(maintenance.lastStatusRefresh().ok()).isFalse();
    }

    @Test
    void unErrorQueNoEsDeBloqueoNoSeReintenta() {
        when(projections.refreshOpenSlaStatuses(any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("Oracle no disponible"));

        assertThat(maintenance.refreshStatuses()).isFalse();

        verify(projections, times(1)).refreshOpenSlaStatuses(any(), any(), any(), any());
    }

    @Test
    void unTramiteQueFallaNoDetieneElRecalculoDeLosDemas() {
        when(projections.findOpenSlaSnapshots()).thenReturn(List.of(
                new SlaSnapshot("a", Instant.parse("2026-12-14T13:00:00Z"), 2),
                new SlaSnapshot("b", Instant.parse("2026-12-14T13:00:00Z"), 2)));
        when(projections.updateSlaDates(eq("a"), any(), any())).thenThrow(new QueryTimeoutException("timeout"));
        when(projections.updateSlaDates(eq("b"), any(), any())).thenReturn(1);

        SlaMaintenance.Recalculation result = maintenance.recalculateOpen();

        assertThat(result).extracting("open", "updated", "failed", "complete").containsExactly(2, 1, 1, false);
    }

    @Test
    void elJobDeLas0005NoCambiaDeDiaConElHorarioDeVerano() {
        CronExpression cron = CronExpression.parse(SlaMaintenance.CRON);
        ZoneId chile = ZoneId.of(SlaMaintenance.ZONE);

        // El domingo 6-sep-2026 no existen las 00:05 y Spring se salta ese día. No importa: un estado solo cambia el
        // día siguiente a un día hábil, así que nunca en domingo.
        ZonedDateTime next = cron.next(ZonedDateTime.of(LocalDateTime.parse("2026-09-05T23:00"), chile));
        assertThat(next.toLocalDateTime()).isEqualTo("2026-09-07T00:05");

        // Domingo 5-abr-2026 las 00:05 existen una sola vez.
        ZonedDateTime april = cron.next(ZonedDateTime.of(LocalDateTime.parse("2026-04-04T23:30"), chile));
        assertThat(april.toLocalDateTime()).isEqualTo("2026-04-05T00:05");
        assertThat(cron.next(april).toLocalDateTime()).isEqualTo("2026-04-06T00:05");
    }
}
