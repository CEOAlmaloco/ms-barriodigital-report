package cl.duoc.barriodigital.report.persistence;

import cl.duoc.barriodigital.report.domain.RequestStatus;

public record MapPoint(
        String requestId,
        String folio,
        Double latitude,
        Double longitude,
        RequestStatus status,
        String procedureType,
        String procedureLabel,
        String sector
) {
}
