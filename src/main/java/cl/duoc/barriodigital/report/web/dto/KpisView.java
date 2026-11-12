package cl.duoc.barriodigital.report.web.dto;

import cl.duoc.barriodigital.report.domain.RequestStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record KpisView(
        String range,
        RequestsOverTime requestsOverTime,
        Map<RequestStatus, Long> activeByStatus,
        BigDecimal averageResolutionHours,
        long resolvedCount,
        SlaCompliance slaCompliance,
        Instant lastEventAt
) {

    public record RequestsOverTime(String bucket, List<Point> points) {
    }

    public record Point(Instant bucketStart, long count) {
    }

    public record SlaCompliance(BigDecimal rate, long onTime, long late) {
    }
}
