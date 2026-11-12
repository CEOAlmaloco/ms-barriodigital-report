package cl.duoc.barriodigital.report.persistence;

import cl.duoc.barriodigital.report.domain.RequestStatus;
import cl.duoc.barriodigital.report.domain.SlaStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Carga proyecciones con SQL, sin pasar por el listener, para probar las consultas de lectura. */
public final class ProjectionRows {

    private static final AtomicInteger FOLIO = new AtomicInteger();

    private final JdbcTemplate jdbc;

    public ProjectionRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clear() {
        jdbc.update("delete from report_request_projection");
    }

    public Row created(String procedureType, String procedureLabel, Instant createdAt) {
        return new Row(procedureType, procedureLabel, RequestStatus.INGRESADO, createdAt, null, null, createdAt);
    }

    /** Proyección parcial: sin alta. */
    public Row partial(RequestStatus status, Instant lastEventAt) {
        return new Row(null, null, status, null, null, null, lastEventAt);
    }

    public final class Row {
        private final String procedureType;
        private final String procedureLabel;
        private final RequestStatus status;
        private final Instant createdAt;
        private final Instant closedAt;
        private final SlaStatus slaStatus;
        private final Instant lastEventAt;
        private ZoneOffset closedOffset = ZoneOffset.UTC;

        private Row(String procedureType, String procedureLabel, RequestStatus status, Instant createdAt,
                    Instant closedAt, SlaStatus slaStatus, Instant lastEventAt) {
            this.procedureType = procedureType;
            this.procedureLabel = procedureLabel;
            this.status = status;
            this.createdAt = createdAt;
            this.closedAt = closedAt;
            this.slaStatus = slaStatus;
            this.lastEventAt = lastEventAt;
        }

        public Row status(RequestStatus newStatus) {
            return new Row(procedureType, procedureLabel, newStatus, createdAt, closedAt, slaStatus, lastEventAt);
        }

        /** También fija lastEventAt en el cierre. */
        public Row closed(RequestStatus closedStatus, Instant at, SlaStatus sla) {
            return new Row(procedureType, procedureLabel, closedStatus, createdAt, at, sla, at);
        }

        /** Mismo instante con otro offset. Va después de closed(), que arma una fila nueva. */
        public Row closedOffset(ZoneOffset offset) {
            this.closedOffset = offset;
            return this;
        }

        public Row lastEventAt(Instant at) {
            return new Row(procedureType, procedureLabel, status, createdAt, closedAt, slaStatus, at);
        }

        public String insert() {
            String requestId = UUID.randomUUID().toString();
            String folio = "BD-2026-" + String.format("%06d", FOLIO.incrementAndGet() % 1_000_000);
            jdbc.update("""
                            insert into report_request_projection
                              (request_id, folio, procedure_type, procedure_label, status, last_sequence,
                               created_at, sla_status, closed_at, last_event_at)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    requestId, folio, procedureType, procedureLabel, status == null ? null : status.name(), 1L,
                    at(createdAt, ZoneOffset.UTC), slaStatus == null ? null : slaStatus.name(),
                    at(closedAt, closedOffset), at(lastEventAt, ZoneOffset.UTC));
            return requestId;
        }
    }

    private static OffsetDateTime at(Instant instant, ZoneOffset offset) {
        return instant == null ? null : instant.atOffset(offset);
    }
}
