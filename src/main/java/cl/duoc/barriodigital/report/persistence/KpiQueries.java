package cl.duoc.barriodigital.report.persistence;

import cl.duoc.barriodigital.report.domain.RequestStatus;
import cl.duoc.barriodigital.report.domain.SlaStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Portables entre Oracle y H2: sin TRUNC ni KEEP, con los tramos calculados afuera. Todas, salvo lastEventAt,
 * excluyen las proyecciones parciales. SUM sobre cero filas es NULL, por eso los conteos van con coalesce.
 *
 * <p>La ventana termina en now, inclusive: un alta posterior al reloj no cuenta.
 */
@Repository
public class KpiQueries {

    private final EntityManager entityManager;

    public KpiQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<Long> createdPerBucket(List<Instant> boundaries) {
        int buckets = boundaries.size() - 1;
        StringBuilder jpql = new StringBuilder("select ");
        for (int i = 0; i < buckets; i++) {
            if (i > 0) {
                jpql.append(", ");
            }
            String upper = i == buckets - 1 ? " <= :b" : " < :b";
            jpql.append("coalesce(sum(case when p.createdAt >= :b").append(i)
                    .append(" and p.createdAt").append(upper).append(i + 1).append(" then 1 else 0 end), 0)");
        }
        jpql.append(" from RequestProjection p where p.createdAt >= :b0 and p.createdAt <= :b").append(buckets);

        TypedQuery<Object[]> query = entityManager.createQuery(jpql.toString(), Object[].class);
        for (int i = 0; i <= buckets; i++) {
            query.setParameter("b" + i, boundaries.get(i));
        }
        Object[] row = buckets == 1 ? new Object[]{query.getSingleResult()} : query.getSingleResult();
        List<Long> counts = new ArrayList<>(buckets);
        for (Object value : row) {
            counts.add(toLong(value));
        }
        return counts;
    }

    /** Los estados sin filas van en 0. */
    public Map<RequestStatus, Long> activeByStatus(Collection<RequestStatus> openStatuses) {
        Map<RequestStatus, Long> result = new EnumMap<>(RequestStatus.class);
        openStatuses.forEach(status -> result.put(status, 0L));
        entityManager.createQuery("""
                        select p.status, count(p) from RequestProjection p
                        where p.createdAt is not null and p.status in :statuses
                        group by p.status""", Object[].class)
                .setParameter("statuses", openStatuses)
                .getResultList()
                .forEach(row -> result.put((RequestStatus) row[0], toLong(row[1])));
        return result;
    }

    /**
     * extract(epoch) normaliza a UTC en Oracle. La resta de fechas de HQL ({@code by second}) resta la hora local
     * de cada valor y falla con offsets distintos.
     */
    public Closures closures(Instant from, Instant to) {
        Object[] row = entityManager.createQuery("""
                        select
                          coalesce(sum(case when p.status = :resolved then 1 else 0 end), 0),
                          avg(case when p.status = :resolved
                                   then extract(epoch from p.closedAt) - extract(epoch from p.createdAt) end),
                          coalesce(sum(case when p.slaStatus = :onTime then 1 else 0 end), 0),
                          coalesce(sum(case when p.slaStatus = :late then 1 else 0 end), 0)
                        from RequestProjection p
                        where p.createdAt is not null and p.closedAt >= :from and p.closedAt <= :to""", Object[].class)
                .setParameter("resolved", RequestStatus.RESUELTO)
                .setParameter("onTime", SlaStatus.CUMPLIDO)
                .setParameter("late", SlaStatus.FUERA_DE_PLAZO)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        Double averageSeconds = row[1] == null ? null : ((Number) row[1]).doubleValue();
        return new Closures(toLong(row[0]), averageSeconds, toLong(row[2]), toLong(row[3]));
    }

    /**
     * El orden y el límite se aplican aquí y no con ORDER BY: en Oracle el ORDER BY de texto sigue NLS_SORT, que el
     * driver toma del locale (SPANISH), y el desempate por tipo cambiaría respecto de H2. Hay una fila por tipo del
     * catálogo, así que ordenar en memoria es barato.
     */
    public Map<String, Long> topProcedureCounts(Instant from, Instant to, int limit) {
        Map<String, Long> result = new LinkedHashMap<>();
        entityManager.createQuery("""
                        select p.procedureType, count(p) from RequestProjection p
                        where p.createdAt >= :from and p.createdAt <= :to
                        group by p.procedureType""", Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList().stream()
                .sorted(Comparator.<Object[]>comparingLong(row -> toLong(row[1])).reversed()
                        .thenComparing(row -> (String) row[0], CodePoints.ORDER))
                .limit(limit)
                .forEach(row -> result.put((String) row[0], toLong(row[1])));
        return result;
    }

    /**
     * Label del alta más reciente en la ventana. Con altas en el mismo instante gana el mayor por code point,
     * comparado aquí: MAX de la base depende de NLS_COMP en Oracle y difiere de H2 fuera del plano básico.
     */
    public Map<String, String> latestLabels(Collection<String> procedureTypes, Instant from, Instant to) {
        Map<String, String> result = new LinkedHashMap<>();
        if (procedureTypes.isEmpty()) {
            return result;
        }
        entityManager.createQuery("""
                        select p.procedureType, p.procedureLabel from RequestProjection p
                        where p.procedureType in :types and p.procedureLabel is not null
                          and p.createdAt = (
                            select max(q.createdAt) from RequestProjection q
                            where q.procedureType = p.procedureType and q.createdAt >= :from and q.createdAt <= :to)
                        """, Object[].class)
                .setParameter("types", procedureTypes)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList()
                .forEach(row -> result.merge((String) row[0], (String) row[1],
                        (current, candidate) -> CodePoints.ORDER.compare(candidate, current) > 0 ? candidate : current));
        return result;
    }

    /** Incluye las proyecciones parciales. */
    public Instant lastEventAt() {
        return entityManager.createQuery("select max(p.lastEventAt) from RequestProjection p", Instant.class)
                .getSingleResult();
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    public record Closures(long resolvedCount, Double averageResolutionSeconds, long onTime, long late) {
    }
}
