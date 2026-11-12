package cl.duoc.barriodigital.report.persistence;

import java.time.Instant;

/** Lo que necesita el recálculo de un trámite abierto: ninguno de estos datos cambia después del alta. */
public record SlaSnapshot(String requestId, Instant createdAt, Integer slaBusinessDays) {
}
