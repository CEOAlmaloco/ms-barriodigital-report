package cl.duoc.barriodigital.report.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public enum ReportRange {

    LAST_24H("last24h", Bucket.HOUR, 24),
    LAST_7D("last7d", Bucket.DAY, 7);

    public enum Bucket { HOUR, DAY }

    public static final ZoneId CHILE = ZoneId.of("America/Santiago");

    private final String value;
    private final Bucket bucket;
    private final int buckets;

    ReportRange(String value, Bucket bucket, int buckets) {
        this.value = value;
        this.bucket = bucket;
        this.buckets = buckets;
    }

    public static Optional<ReportRange> fromValue(String value) {
        for (ReportRange range : values()) {
            if (range.value.equals(value)) {
                return Optional.of(range);
            }
        }
        return Optional.empty();
    }

    public String value() {
        return value;
    }

    public Bucket bucket() {
        return bucket;
    }

    /**
     * buckets + 1 límites: el inicio de cada tramo y, al final, now. Los días se cortan en America/Santiago: un día
     * con cambio de horario dura 23 o 25 horas.
     */
    public List<Instant> boundaries(Instant now) {
        List<Instant> limits = new ArrayList<>(buckets + 1);
        if (bucket == Bucket.HOUR) {
            Instant first = now.truncatedTo(ChronoUnit.HOURS).minus(buckets - 1L, ChronoUnit.HOURS);
            for (int i = 0; i < buckets; i++) {
                limits.add(first.plus(i, ChronoUnit.HOURS));
            }
        } else {
            LocalDate first = LocalDate.ofInstant(now, CHILE).minusDays(buckets - 1L);
            for (int i = 0; i < buckets; i++) {
                limits.add(first.plusDays(i).atStartOfDay(CHILE).toInstant());
            }
        }
        limits.add(now);
        return List.copyOf(limits);
    }
}
