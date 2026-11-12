package cl.duoc.barriodigital.report.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportRangeTest {

    @Test
    void last24hTiene24TramosAlineadosALaHoraUtcYTerminaEnNow() {
        Instant now = Instant.parse("2026-11-10T15:20:30Z");
        List<Instant> limits = ReportRange.LAST_24H.boundaries(now);

        assertThat(limits).hasSize(25);
        assertThat(limits.get(0)).isEqualTo("2026-11-09T16:00:00Z");
        assertThat(limits.get(23)).isEqualTo("2026-11-10T15:00:00Z");
        assertThat(limits.get(24)).isEqualTo(now);
    }

    @Test
    void last7dCortaLosDiasEnChileYTerminaEnNow() {
        // 02:00 UTC del 10-nov todavía es 9-nov en Chile (UTC-3).
        Instant now = Instant.parse("2026-11-10T02:00:00Z");
        List<Instant> limits = ReportRange.LAST_7D.boundaries(now);

        assertThat(limits).hasSize(8);
        assertThat(limits.get(0)).isEqualTo("2026-11-03T03:00:00Z");
        assertThat(limits.get(6)).isEqualTo("2026-11-09T03:00:00Z");
        assertThat(limits.get(7)).isEqualTo(now);
    }

    @Test
    void elDiaDelInicioDelHorarioDeVeranoDura23Horas() {
        // Chile adelanta la hora el domingo 6-sep-2026: de UTC-4 a UTC-3.
        List<Instant> limits = ReportRange.LAST_7D.boundaries(Instant.parse("2026-09-08T15:00:00Z"));

        assertThat(limits.get(0)).isEqualTo("2026-09-02T04:00:00Z");
        assertThat(limits.get(4)).isEqualTo("2026-09-06T04:00:00Z");
        assertThat(limits.get(5)).isEqualTo("2026-09-07T03:00:00Z");
        assertThat(Duration.between(limits.get(4), limits.get(5))).isEqualTo(Duration.ofHours(23));
    }

    @Test
    void elDiaDelFinDelHorarioDeVeranoDura25Horas() {
        // Chile atrasa la hora al terminar el sábado 4-abr-2026: de UTC-3 a UTC-4, y ese día dura 25 horas.
        List<Instant> limits = ReportRange.LAST_7D.boundaries(Instant.parse("2026-04-07T15:00:00Z"));

        assertThat(limits.get(3)).isEqualTo("2026-04-04T03:00:00Z");
        assertThat(limits.get(4)).isEqualTo("2026-04-05T04:00:00Z");
        assertThat(Duration.between(limits.get(3), limits.get(4))).isEqualTo(Duration.ofHours(25));
    }

    @Test
    void soloAceptaLosValoresExactos() {
        assertThat(ReportRange.fromValue("last24h")).contains(ReportRange.LAST_24H);
        assertThat(ReportRange.fromValue("last7d")).contains(ReportRange.LAST_7D);
        assertThat(ReportRange.fromValue("LAST24H")).isEmpty();
        assertThat(ReportRange.fromValue("")).isEmpty();
    }
}
