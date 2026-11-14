package cl.duoc.barriodigital.report.service;

import cl.duoc.barriodigital.report.domain.RequestStatus;
import cl.duoc.barriodigital.report.persistence.KpiQueries;
import cl.duoc.barriodigital.report.persistence.MapPointQueries;
import cl.duoc.barriodigital.report.web.dto.MapPointsView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Serializable para que los puntos, los totales y lastEventAt salgan de la misma foto aunque el listener confirme
 * eventos entremedio. Es de solo lectura, así que no aborta por conflictos de escritura.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
public class MapPointsQueryService {

    public static final int MAX_POINTS = 1000;

    private final MapPointQueries queries;
    private final KpiQueries kpiQueries;
    private final Clock clock;

    public MapPointsQueryService(MapPointQueries queries, KpiQueries kpiQueries, Clock clock) {
        this.queries = queries;
        this.kpiQueries = kpiQueries;
        this.clock = clock;
    }

    public MapPointsView mapPoints(ReportRange range, Set<RequestStatus> statuses, String procedureType) {
        List<Instant> boundaries = range.boundaries(clock.instant());
        MapPointQueries.Filter filter = new MapPointQueries.Filter(
                boundaries.get(0), boundaries.get(boundaries.size() - 1), statuses, procedureType);

        List<MapPointQueries.SectorCount> sectors = queries.sectorCounts(filter);
        long located = sectors.stream().mapToLong(MapPointQueries.SectorCount::located).sum();
        long total = sectors.stream().mapToLong(MapPointQueries.SectorCount::total).sum();

        List<MapPointsView.Item> items = located == 0 ? List.of() : queries.points(filter, MAX_POINTS).stream()
                .map(p -> new MapPointsView.Item(p.requestId(), p.folio(), p.latitude(), p.longitude(), p.status(),
                        p.procedureType(), p.procedureLabel(), p.sector()))
                .toList();
        List<MapPointsView.SectorCount> bySector = sectors.stream()
                .map(s -> new MapPointsView.SectorCount(s.sector(), s.total()))
                .toList();
        return new MapPointsView(range.value(), items, located, total - located, bySector, kpiQueries.lastEventAt());
    }
}
