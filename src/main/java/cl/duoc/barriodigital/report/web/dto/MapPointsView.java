package cl.duoc.barriodigital.report.web.dto;

import cl.duoc.barriodigital.report.domain.RequestStatus;

import java.time.Instant;
import java.util.List;

/** bySector suma locatedTotal + withoutLocation: no depende del tope de items. */
public record MapPointsView(
        String range,
        List<Item> items,
        long locatedTotal,
        long withoutLocation,
        List<SectorCount> bySector,
        Instant lastEventAt
) {

    public record Item(
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

    public record SectorCount(String sector, long count) {
    }
}
