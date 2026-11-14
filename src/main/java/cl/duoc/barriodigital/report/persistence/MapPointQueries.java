package cl.duoc.barriodigital.report.persistence;

import cl.duoc.barriodigital.report.domain.RequestStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Mismos filtros para los puntos y los conteos. La ventana sobre createdAt deja fuera las proyecciones parciales y
 * termina en now, inclusive, como en KpiQueries.
 */
@Repository
public class MapPointQueries {

    private static final String LOCATED = "p.latitude is not null and p.longitude is not null";

    /** El ítem sin sector va siempre al final, aunque sea el de más trámites. */
    private static final Comparator<SectorCount> SECTOR_ORDER = Comparator
            .comparing((SectorCount s) -> s.sector() == null)
            .thenComparing(Comparator.comparingLong(SectorCount::total).reversed())
            .thenComparing(SectorCount::sector, Comparator.nullsLast(CodePoints.ORDER));

    private final EntityManager entityManager;

    public MapPointQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * requestId desempata con ORDER BY de la base: todos los UUID tienen el mismo formato en minúsculas, así que el
     * orden lingüístico de Oracle coincide con el binario de H2.
     */
    public List<MapPoint> points(Filter filter, int max) {
        String jpql = """
                select new cl.duoc.barriodigital.report.persistence.MapPoint(
                  p.requestId, p.folio, p.latitude, p.longitude, p.status, p.procedureType, p.procedureLabel, p.sector)
                from RequestProjection p""" + where(filter) + " and " + LOCATED
                + " order by p.createdAt desc, p.requestId asc";
        TypedQuery<MapPoint> query = entityManager.createQuery(jpql, MapPoint.class);
        bind(query, filter);
        return query.setMaxResults(max).getResultList();
    }

    /** El orden se resuelve aquí, no con ORDER BY: en Oracle el texto se ordena según NLS_SORT. */
    public List<SectorCount> sectorCounts(Filter filter) {
        String jpql = "select p.sector, count(p), sum(case when " + LOCATED + " then 1 else 0 end)"
                + " from RequestProjection p" + where(filter) + " group by p.sector";
        TypedQuery<Object[]> query = entityManager.createQuery(jpql, Object[].class);
        bind(query, filter);
        return query.getResultList().stream()
                .map(row -> new SectorCount((String) row[0], toLong(row[1]), toLong(row[2])))
                .sorted(SECTOR_ORDER)
                .toList();
    }

    private static String where(Filter filter) {
        String where = " where p.createdAt >= :from and p.createdAt <= :to and p.status in :statuses";
        return filter.procedureType() == null ? where : where + " and p.procedureType = :procedureType";
    }

    private static void bind(TypedQuery<?> query, Filter filter) {
        query.setParameter("from", filter.from())
                .setParameter("to", filter.to())
                .setParameter("statuses", filter.statuses());
        if (filter.procedureType() != null) {
            query.setParameter("procedureType", filter.procedureType());
        }
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    /** statuses nunca va vacío: sin filtro de estado lleva los siete. procedureType null es sin filtro. */
    public record Filter(Instant from, Instant to, Set<RequestStatus> statuses, String procedureType) {
    }

    public record SectorCount(String sector, long total, long located) {
    }
}
