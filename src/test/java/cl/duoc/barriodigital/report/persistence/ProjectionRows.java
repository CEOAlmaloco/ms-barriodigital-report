package cl.duoc.barriodigital.report.persistence;

import cl.duoc.barriodigital.report.domain.RequestStatus;
import cl.duoc.barriodigital.report.domain.SlaStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Carga proyecciones con SQL, sin pasar por el listener, para probar las consultas y los procesos derivados. */
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
        Row row = new Row();
        row.procedureType = procedureType;
        row.procedureLabel = procedureLabel;
        row.status = RequestStatus.INGRESADO;
        row.createdAt = createdAt;
        row.lastEventAt = createdAt;
        return row;
    }

    /** Proyección parcial: sin alta. */
    public Row partial(RequestStatus status, Instant lastEventAt) {
        Row row = new Row();
        row.status = status;
        row.lastEventAt = lastEventAt;
        return row;
    }

    public final class Row {
        private String requestId = UUID.randomUUID().toString();
        private String folio = "BD-2026-" + String.format("%06d", FOLIO.incrementAndGet() % 1_000_000);
        private String procedureType;
        private String procedureLabel;
        private RequestStatus status;
        private Instant createdAt;
        private Instant closedAt;
        private ZoneOffset closedOffset = ZoneOffset.UTC;
        private SlaStatus slaStatus;
        private Integer slaBusinessDays;
        private LocalDate dueDate;
        private LocalDate dueSoonDate;
        private String assigneeId;
        private String assigneeName;
        private Instant lastEventAt;

        private Row() {
        }

        public Row status(RequestStatus newStatus) {
            status = newStatus;
            return this;
        }

        /** También fija lastEventAt en el cierre. */
        public Row closed(RequestStatus closedStatus, Instant at, SlaStatus sla) {
            status = closedStatus;
            closedAt = at;
            slaStatus = sla;
            lastEventAt = at;
            return this;
        }

        /** Mismo instante de cierre, guardado con otro offset. */
        public Row closedOffset(ZoneOffset offset) {
            closedOffset = offset;
            return this;
        }

        public Row lastEventAt(Instant at) {
            lastEventAt = at;
            return this;
        }

        public Row sla(Integer businessDays, LocalDate due, LocalDate dueSoon, SlaStatus sla) {
            slaBusinessDays = businessDays;
            dueDate = due;
            dueSoonDate = dueSoon;
            slaStatus = sla;
            return this;
        }

        public Row slaStatus(SlaStatus sla) {
            slaStatus = sla;
            return this;
        }

        public Row assignee(String id, String name) {
            assigneeId = id;
            assigneeName = name;
            return this;
        }

        public Row ids(String id, String newFolio) {
            requestId = id;
            folio = newFolio;
            return this;
        }

        public String insert() {
            jdbc.update("""
                            insert into report_request_projection
                              (request_id, folio, procedure_type, procedure_label, status, last_sequence,
                               created_at, sla_business_days, due_date, due_soon_date, sla_status, closed_at,
                               assignee_id, assignee_name, last_event_at)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    requestId, folio, procedureType, procedureLabel, status == null ? null : status.name(), 1L,
                    at(createdAt, ZoneOffset.UTC), slaBusinessDays, dueDate, dueSoonDate,
                    slaStatus == null ? null : slaStatus.name(), at(closedAt, closedOffset),
                    assigneeId, assigneeName, at(lastEventAt, ZoneOffset.UTC));
            return requestId;
        }
    }

    private static OffsetDateTime at(Instant instant, ZoneOffset offset) {
        return instant == null ? null : instant.atOffset(offset);
    }
}
