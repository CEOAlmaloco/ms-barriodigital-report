package cl.duoc.barriodigital.report.persistence;

import cl.duoc.barriodigital.report.domain.RequestProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RequestProjectionRepository extends JpaRepository<RequestProjection, String> {

    /** Protege la lectura del listener frente al recálculo de plazos, que escribe la misma fila. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RequestProjection p where p.requestId = :requestId")
    Optional<RequestProjection> findByIdForUpdate(@Param("requestId") String requestId);
}
