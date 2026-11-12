package cl.duoc.barriodigital.report.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Foto por trámite que leen los KPIs y los plazos, sin contadores compartidos.
 *
 * <p>El estado y el responsable guardan cada uno el sequence del último evento que los cambió: un evento atrasado
 * no cambia su aspecto, y uno de otro aspecto no lo bloquea. El alta y el cierre se completan si faltan, aunque el
 * evento llegue atrasado.
 *
 * <p>Sin createdAt es una proyección parcial: no cuenta en los KPIs ni en los plazos.
 */
@Entity
@Table(name = "report_request_projection", indexes = {
        @Index(name = "idx_report_projection_created", columnList = "created_at"),
        @Index(name = "idx_report_projection_closed", columnList = "closed_at"),
        @Index(name = "idx_report_projection_status", columnList = "status"),
        @Index(name = "idx_report_projection_sla_status", columnList = "sla_status"),
        @Index(name = "idx_report_projection_due_date", columnList = "due_date"),
        @Index(name = "idx_report_projection_assignee", columnList = "assignee_id"),
        @Index(name = "idx_report_projection_last_event", columnList = "last_event_at")
})
public class RequestProjection implements Persistable<String> {

    @Id
    @Column(name = "request_id", length = 36, nullable = false)
    private String requestId;

    @Column(name = "folio", length = 14, nullable = false)
    private String folio;

    @Column(name = "procedure_type", length = 100)
    private String procedureType;

    @Column(name = "procedure_label", length = 120)
    private String procedureLabel;

    @Column(name = "sector", length = 40)
    private String sector;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30)
    private RequestStatus status;

    @Column(name = "status_sequence")
    private Long statusSequence;

    @Column(name = "assignee_id", length = 64)
    private String assigneeId;

    @Column(name = "assignee_name", length = 200)
    private String assigneeName;

    @Column(name = "assignee_sequence")
    private Long assigneeSequence;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "sla_business_days")
    private Integer slaBusinessDays;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "due_soon_date")
    private LocalDate dueSoonDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "sla_status", length = 20)
    private SlaStatus slaStatus;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    @Transient
    private boolean isNew;

    protected RequestProjection() {
    }

    /** Trámite que report aún no conoce: apply la llena con el primer evento. */
    public static RequestProjection empty(String requestId, String folio) {
        RequestProjection projection = new RequestProjection();
        projection.requestId = requestId;
        projection.folio = folio;
        projection.isNew = true;
        return projection;
    }

    public void apply(RequestEvent event) {
        long sequence = event.sequence();
        switch (event.change()) {
            case RequestEvent.Created created -> applyCreated(created, event.occurredAt(), sequence);
            case RequestEvent.StatusChanged changed -> {
                fillDescriptive(changed.procedureType(), changed.sector());
                applyStatus(changed.toStatus(), event.occurredAt(), sequence);
            }
            case RequestEvent.Assigned assigned -> applyAssigned(assigned, sequence);
            case RequestEvent.Activity ignored -> {
            }
        }
        if (sequence > lastSequence) {
            lastSequence = sequence;
        }
        if (lastEventAt == null || event.occurredAt().isAfter(lastEventAt)) {
            lastEventAt = event.occurredAt();
        }
    }

    private void applyCreated(RequestEvent.Created created, Instant occurredAt, long sequence) {
        if (createdAt == null) {
            createdAt = occurredAt;
            procedureType = created.procedureType();
            procedureLabel = created.procedureLabel();
            sector = created.sector();
            latitude = created.latitude();
            longitude = created.longitude();
            slaBusinessDays = created.slaBusinessDays();
        }
        applyStatus(RequestStatus.INGRESADO, occurredAt, sequence);
    }

    private void applyStatus(RequestStatus toStatus, Instant occurredAt, long sequence) {
        if (toStatus.isClosed() && closedAt == null) {
            closedAt = occurredAt;
        }
        if (statusSequence == null || sequence > statusSequence) {
            status = toStatus;
            statusSequence = sequence;
        }
    }

    private void applyAssigned(RequestEvent.Assigned assigned, long sequence) {
        if (assigneeSequence == null || sequence > assigneeSequence) {
            assigneeId = assigned.assigneeId();
            assigneeName = assigned.assigneeName();
            assigneeSequence = sequence;
        }
    }

    /** Tipo y sector no cambian en la vida del trámite: solo se completan si faltan. */
    private void fillDescriptive(String eventProcedureType, String eventSector) {
        if (procedureType == null) {
            procedureType = eventProcedureType;
        }
        if (sector == null) {
            sector = eventSector;
        }
    }

    public boolean isPartial() {
        return createdAt == null;
    }

    @Override
    public String getId() {
        return requestId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getFolio() {
        return folio;
    }

    public String getProcedureType() {
        return procedureType;
    }

    public String getProcedureLabel() {
        return procedureLabel;
    }

    public String getSector() {
        return sector;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public Long getStatusSequence() {
        return statusSequence;
    }

    public String getAssigneeId() {
        return assigneeId;
    }

    public String getAssigneeName() {
        return assigneeName;
    }

    public Long getAssigneeSequence() {
        return assigneeSequence;
    }

    public long getLastSequence() {
        return lastSequence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Integer getSlaBusinessDays() {
        return slaBusinessDays;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDate getDueSoonDate() {
        return dueSoonDate;
    }

    public SlaStatus getSlaStatus() {
        return slaStatus;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }
}
