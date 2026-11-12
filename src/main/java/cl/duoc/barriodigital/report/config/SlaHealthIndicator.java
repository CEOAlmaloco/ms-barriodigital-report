package cl.duoc.barriodigital.report.config;

import cl.duoc.barriodigital.report.domain.BusinessCalendar;
import cl.duoc.barriodigital.report.service.SlaMaintenance;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Componente sla del health. Informativo: siempre UP, porque la reportería no es el core. */
@Component
public class SlaHealthIndicator implements HealthIndicator {

    private final BusinessCalendar calendar;
    private final SlaMaintenance maintenance;

    public SlaHealthIndicator(BusinessCalendar calendar, SlaMaintenance maintenance) {
        this.calendar = calendar;
        this.maintenance = maintenance;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up()
                .withDetail("coveredYears", calendar.coveredYears())
                .withDetail("missingYears", calendar.missingYears());
        SlaMaintenance.PendingEvaluation pending = maintenance.lastPendingEvaluation();
        if (pending != null) {
            health.withDetail("startupPendingEvaluation", pending);
        }
        SlaMaintenance.Recalculation recalculation = maintenance.lastRecalculation();
        if (recalculation != null) {
            health.withDetail("startupRecalculation", recalculation);
        }
        SlaMaintenance.StatusRefresh refresh = maintenance.lastStatusRefresh();
        if (refresh != null) {
            health.withDetail("lastStatusRefresh", refresh);
        }
        return health.build();
    }
}
