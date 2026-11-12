package cl.duoc.barriodigital.report.service;

import cl.duoc.barriodigital.report.domain.RequestProjection;
import cl.duoc.barriodigital.report.domain.SlaCalculator;
import cl.duoc.barriodigital.report.domain.SlaStatus;
import cl.duoc.barriodigital.report.persistence.RequestProjectionRepository;
import cl.duoc.barriodigital.report.persistence.SlaSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Evaluación de pendientes, recálculo de fechas y UPDATE de estado. Los tres son idempotentes, así que dos instancias
 * pueden correrlos a la vez sin un bloqueo distribuido.
 */
@Component
public class SlaMaintenance {

    public static final String CRON = "0 5 0 * * *";
    public static final String ZONE = "America/Santiago";
    static final int STATUS_ATTEMPTS = 2;

    private static final Logger log = LoggerFactory.getLogger(SlaMaintenance.class);

    private final RequestProjectionRepository projections;
    private final SlaCalculator calculator;
    private final TransactionTemplate transactions;
    private final Clock clock;

    private volatile PendingEvaluation lastPendingEvaluation;
    private volatile Recalculation lastRecalculation;
    private volatile StatusRefresh lastStatusRefresh;

    public SlaMaintenance(RequestProjectionRepository projections, SlaCalculator calculator,
                          TransactionTemplate transactions, Clock clock) {
        this.projections = projections;
        this.calculator = calculator;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Altas con sla_status nulo, proyectadas antes de que report calculara plazos: sin esto, un trámite sin plazo
     * quedaría "sin dato" y uno cerrado con plazo nunca entraría en el cumplimiento. El cálculo es el mismo de
     * refreshSla, sobre la entidad ya separada de la transacción, y se escribe con un UPDATE acotado.
     */
    public PendingEvaluation evaluatePending() {
        List<RequestProjection> pending;
        try {
            pending = transactions.execute(status -> projections.findPendingSlaEvaluation());
        } catch (RuntimeException ex) {
            log.error("plazos-pendientes-fallido paso=listado", ex);
            lastPendingEvaluation = new PendingEvaluation(clock.instant(), 0, 0, 0, false);
            return lastPendingEvaluation;
        }
        LocalDate today = LocalDate.now(clock.withZone(SlaCalculator.ZONE));
        int evaluated = 0;
        int failed = 0;
        for (RequestProjection projection : pending) {
            try {
                projection.refreshSla(calculator, today);
                Integer rows = transactions.execute(status -> projections.updatePendingSla(projection.getRequestId(),
                        projection.getDueDate(), projection.getDueSoonDate(), projection.getSlaStatus()));
                evaluated += rows == null ? 0 : rows;
            } catch (RuntimeException ex) {
                failed++;
                log.error("plazos-pendientes-fallido requestId={}", projection.getRequestId(), ex);
            }
        }
        log.info("plazos-pendientes-evaluados pendientes={} evaluados={} fallidos={}",
                pending.size(), evaluated, failed);
        lastPendingEvaluation = new PendingEvaluation(clock.instant(), pending.size(), evaluated, failed, failed == 0);
        return lastPendingEvaluation;
    }

    /**
     * Un trámite por transacción, por id ascendente: cada transacción bloquea una sola fila y no puede formar un ciclo
     * con el listener. Un trámite que falla no detiene a los demás.
     */
    public Recalculation recalculateOpen() {
        List<SlaSnapshot> open;
        try {
            open = transactions.execute(status -> projections.findOpenSlaSnapshots());
        } catch (RuntimeException ex) {
            log.error("plazos-recalculo-fallido paso=listado", ex);
            lastRecalculation = new Recalculation(clock.instant(), 0, 0, 0, false);
            return lastRecalculation;
        }
        int updated = 0;
        int failed = 0;
        for (SlaSnapshot snapshot : open) {
            try {
                SlaCalculator.SlaDates dates = calculator.dates(snapshot.createdAt(), snapshot.slaBusinessDays());
                Integer rows = transactions.execute(status -> projections.updateSlaDates(
                        snapshot.requestId(), dates.dueDate(), dates.dueSoonDate()));
                updated += rows == null ? 0 : rows;
            } catch (RuntimeException ex) {
                failed++;
                log.error("plazos-recalculo-fallido requestId={}", snapshot.requestId(), ex);
            }
        }
        log.info("plazos-recalculados abiertos={} actualizados={} fallidos={}", open.size(), updated, failed);
        lastRecalculation = new Recalculation(clock.instant(), open.size(), updated, failed, failed == 0);
        return lastRecalculation;
    }

    /**
     * Ante ORA-00060 Oracle revierte la sentencia completa. El reintento va en una transacción nueva: la anterior ya
     * terminó con rollback y no se reutiliza.
     */
    public boolean refreshStatuses() {
        LocalDate today = LocalDate.now(clock.withZone(SlaCalculator.ZONE));
        for (int attempt = 1; attempt <= STATUS_ATTEMPTS; attempt++) {
            try {
                Integer rows = transactions.execute(status -> projections.refreshOpenSlaStatuses(
                        today, SlaStatus.VENCIDO, SlaStatus.POR_VENCER, SlaStatus.DENTRO_DE_PLAZO));
                log.info("plazos-estado-actualizado hoy={} filas={}", today, rows);
                lastStatusRefresh = new StatusRefresh(clock.instant(), true);
                return true;
            } catch (PessimisticLockingFailureException ex) {
                if (attempt < STATUS_ATTEMPTS) {
                    log.warn("plazos-estado-bloqueo hoy={} intento={}: se reintenta", today, attempt);
                } else {
                    log.error("plazos-estado-fallido hoy={} intentos={}: lo corrige la siguiente ejecucion",
                            today, attempt, ex);
                }
            } catch (RuntimeException ex) {
                log.error("plazos-estado-fallido hoy={}", today, ex);
                break;
            }
        }
        lastStatusRefresh = new StatusRefresh(clock.instant(), false);
        return false;
    }

    @Scheduled(cron = CRON, zone = ZONE)
    public void daily() {
        refreshStatuses();
    }

    public PendingEvaluation lastPendingEvaluation() {
        return lastPendingEvaluation;
    }

    public Recalculation lastRecalculation() {
        return lastRecalculation;
    }

    public StatusRefresh lastStatusRefresh() {
        return lastStatusRefresh;
    }

    public record PendingEvaluation(Instant at, int pending, int evaluated, int failed, boolean complete) {
    }

    public record Recalculation(Instant at, int open, int updated, int failed, boolean complete) {
    }

    public record StatusRefresh(Instant at, boolean ok) {
    }
}
