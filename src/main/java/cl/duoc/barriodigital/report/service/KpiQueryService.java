package cl.duoc.barriodigital.report.service;

import cl.duoc.barriodigital.report.domain.RequestStatus;
import cl.duoc.barriodigital.report.persistence.KpiQueries;
import cl.duoc.barriodigital.report.web.dto.KpisView;
import cl.duoc.barriodigital.report.web.dto.TopProceduresView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** El reloj se lee una vez por petición: todas sus consultas cortan en el mismo now. */
@Service
@Transactional(readOnly = true)
public class KpiQueryService {

    private static final List<RequestStatus> OPEN_STATUSES = List.of(
            RequestStatus.INGRESADO, RequestStatus.ADMITIDO, RequestStatus.EN_GESTION, RequestStatus.EN_TERRENO);
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    private final KpiQueries queries;
    private final Clock clock;

    public KpiQueryService(KpiQueries queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    public KpisView kpis(ReportRange range) {
        List<Instant> boundaries = range.boundaries(clock.instant());
        Instant from = boundaries.get(0);
        Instant to = boundaries.get(boundaries.size() - 1);

        List<Long> counts = queries.createdPerBucket(boundaries);
        List<KpisView.Point> points = new ArrayList<>(counts.size());
        for (int i = 0; i < counts.size(); i++) {
            points.add(new KpisView.Point(boundaries.get(i), counts.get(i)));
        }

        KpiQueries.Closures closures = queries.closures(from, to);
        return new KpisView(
                range.value(),
                new KpisView.RequestsOverTime(range.bucket().name(), List.copyOf(points)),
                queries.activeByStatus(OPEN_STATUSES),
                hours(closures.averageResolutionSeconds()),
                closures.resolvedCount(),
                new KpisView.SlaCompliance(rate(closures.onTime(), closures.late()), closures.onTime(), closures.late()),
                queries.lastEventAt());
    }

    public TopProceduresView topProcedures(ReportRange range, int limit) {
        List<Instant> boundaries = range.boundaries(clock.instant());
        Instant from = boundaries.get(0);
        Instant to = boundaries.get(boundaries.size() - 1);

        Map<String, Long> counts = queries.topProcedureCounts(from, to, limit);
        Map<String, String> labels = queries.latestLabels(counts.keySet(), from, to);
        List<TopProceduresView.Item> items = counts.entrySet().stream()
                .map(entry -> new TopProceduresView.Item(entry.getKey(), labels.get(entry.getKey()), entry.getValue()))
                .toList();
        return new TopProceduresView(range.value(), items, queries.lastEventAt());
    }

    static BigDecimal hours(Double seconds) {
        return seconds == null ? null
                : BigDecimal.valueOf(seconds).divide(SECONDS_PER_HOUR, 1, RoundingMode.HALF_UP);
    }

    static BigDecimal rate(long onTime, long late) {
        long total = onTime + late;
        return total == 0 ? null
                : BigDecimal.valueOf(onTime).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }
}
