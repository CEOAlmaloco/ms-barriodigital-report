package cl.duoc.barriodigital.report.persistence;

import cl.duoc.barriodigital.report.domain.RequestProjection;
import cl.duoc.barriodigital.report.domain.SlaStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Los procesos derivados nunca leen la fila para guardarla completa: escriben solo sus columnas con un UPDATE, y
 * closed_at IS NULL en el WHERE deja fuera un trámite que se cerró entremedio.
 */
public interface RequestProjectionRepository extends JpaRepository<RequestProjection, String> {

    /** Protege la lectura del listener frente al recálculo de plazos, que escribe la misma fila. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RequestProjection p where p.requestId = :requestId")
    Optional<RequestProjection> findByIdForUpdate(@Param("requestId") String requestId);

    /** Sin plazo y parciales quedan fuera: no tienen desde dónde contar. */
    @Query("""
            select new cl.duoc.barriodigital.report.persistence.SlaSnapshot(p.requestId, p.createdAt, p.slaBusinessDays)
            from RequestProjection p
            where p.closedAt is null and p.createdAt is not null and p.slaBusinessDays is not null
            order by p.requestId""")
    List<SlaSnapshot> findOpenSlaSnapshots();

    /** Altas proyectadas antes de que report calculara plazos: nunca se evaluaron. */
    @Query("""
            select p from RequestProjection p
            where p.createdAt is not null and p.slaStatus is null
            order by p.requestId""")
    List<RequestProjection> findPendingSlaEvaluation();

    /** sla_status IS NULL deja fuera una fila que el listener evaluó entremedio. */
    @Modifying
    @Query("""
            update RequestProjection p
            set p.dueDate = :dueDate, p.dueSoonDate = :dueSoonDate, p.slaStatus = :slaStatus
            where p.requestId = :requestId and p.slaStatus is null""")
    int updatePendingSla(@Param("requestId") String requestId, @Param("dueDate") LocalDate dueDate,
                         @Param("dueSoonDate") LocalDate dueSoonDate, @Param("slaStatus") SlaStatus slaStatus);

    @Modifying
    @Query("""
            update RequestProjection p set p.dueDate = :dueDate, p.dueSoonDate = :dueSoonDate
            where p.requestId = :requestId and p.closedAt is null""")
    int updateSlaDates(@Param("requestId") String requestId, @Param("dueDate") LocalDate dueDate,
                       @Param("dueSoonDate") LocalDate dueSoonDate);

    /** Una sola sentencia, excluyente por construcción: también devuelve un VENCIDO si el recálculo postergó la fecha. */
    @Modifying
    @Query("""
            update RequestProjection p set p.slaStatus = case
                when p.dueDate < :today then :overdue
                when p.dueSoonDate <= :today then :dueSoon
                else :onTime end
            where p.closedAt is null and p.dueDate is not null""")
    int refreshOpenSlaStatuses(@Param("today") LocalDate today, @Param("overdue") SlaStatus overdue,
                               @Param("dueSoon") SlaStatus dueSoon, @Param("onTime") SlaStatus onTime);

    @Query("""
            select p from RequestProjection p
            where p.closedAt is null and p.createdAt is not null and p.slaStatus in :statuses
            order by p.dueDate asc, p.folio asc""")
    Page<RequestProjection> findOpenAlerts(@Param("statuses") Collection<SlaStatus> statuses, Pageable page);

    @Query("""
            select p from RequestProjection p
            where p.closedAt is null and p.createdAt is not null and p.slaStatus in :statuses
              and p.assigneeId = :assigneeId
            order by p.dueDate asc, p.folio asc""")
    Page<RequestProjection> findOpenAlertsByAssignee(@Param("statuses") Collection<SlaStatus> statuses,
                                                     @Param("assigneeId") String assigneeId, Pageable page);

    @Query("""
            select p from RequestProjection p
            where p.closedAt is null and p.createdAt is not null and p.slaStatus in :statuses
              and p.assigneeId is null
            order by p.dueDate asc, p.folio asc""")
    Page<RequestProjection> findOpenAlertsWithoutAssignee(@Param("statuses") Collection<SlaStatus> statuses,
                                                          Pageable page);
}
