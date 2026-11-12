package cl.duoc.barriodigital.report.service;

import cl.duoc.barriodigital.report.domain.SlaStatus;
import cl.duoc.barriodigital.report.persistence.KpiQueries;
import cl.duoc.barriodigital.report.persistence.ProjectionRows;
import cl.duoc.barriodigital.report.web.dto.KpisView;
import cl.duoc.barriodigital.report.web.dto.TopProceduresView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static cl.duoc.barriodigital.report.domain.RequestStatus.ADMITIDO;
import static cl.duoc.barriodigital.report.domain.RequestStatus.CANCELADO;
import static cl.duoc.barriodigital.report.domain.RequestStatus.EN_GESTION;
import static cl.duoc.barriodigital.report.domain.RequestStatus.EN_TERRENO;
import static cl.duoc.barriodigital.report.domain.RequestStatus.INGRESADO;
import static cl.duoc.barriodigital.report.domain.RequestStatus.RECHAZADO;
import static cl.duoc.barriodigital.report.domain.RequestStatus.RESUELTO;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KpiQueryServiceH2Test {

    /** last24h: de 2026-11-09T16:00Z a NOW. last7d: de 2026-11-04T03:00Z a NOW. */
    private static final Instant NOW = Instant.parse("2026-11-10T15:20:00Z");
    private static final Instant START_24H = Instant.parse("2026-11-09T16:00:00Z");

    @Autowired
    private KpiQueries queries;

    @Autowired
    private JdbcTemplate jdbc;

    private ProjectionRows rows;

    @BeforeEach
    void clean() {
        rows = new ProjectionRows(jdbc);
        rows.clear();
    }

    @Test
    void sinProyeccionesDaCerosYNulos() {
        KpisView kpis = service(NOW).kpis(ReportRange.LAST_24H);

        assertThat(kpis.range()).isEqualTo("last24h");
        assertThat(kpis.requestsOverTime().bucket()).isEqualTo("HOUR");
        assertThat(kpis.requestsOverTime().points()).hasSize(24).allSatisfy(p -> assertThat(p.count()).isZero());
        assertThat(kpis.requestsOverTime().points().get(0).bucketStart()).isEqualTo(START_24H);
        assertThat(kpis.activeByStatus()).containsExactly(
                Map.entry(INGRESADO, 0L), Map.entry(ADMITIDO, 0L), Map.entry(EN_GESTION, 0L), Map.entry(EN_TERRENO, 0L));
        assertThat(kpis.averageResolutionHours()).isNull();
        assertThat(kpis.resolvedCount()).isZero();
        assertThat(kpis.slaCompliance().rate()).isNull();
        assertThat(kpis.slaCompliance().onTime()).isZero();
        assertThat(kpis.slaCompliance().late()).isZero();
        assertThat(kpis.lastEventAt()).isNull();

        TopProceduresView top = service(NOW).topProcedures(ReportRange.LAST_7D, 5);
        assertThat(top.items()).isEmpty();
        assertThat(top.lastEventAt()).isNull();
    }

    @Test
    void conFilasQueNoCalzanLosConteosSiguenEnCero() {
        // Hay filas, pero ninguna resuelta ni con plazo: los SUM(CASE) dan 0, no null.
        rows.created("bache", "Bache", NOW.minus(Duration.ofHours(2)))
                .closed(RECHAZADO, NOW.minus(Duration.ofHours(1)), SlaStatus.SIN_PLAZO).insert();
        // Fuera de la ventana.
        rows.created("bache", "Bache", START_24H.minusSeconds(1)).insert();

        KpisView kpis = service(NOW).kpis(ReportRange.LAST_24H);

        assertThat(kpis.requestsOverTime().points().stream().mapToLong(KpisView.Point::count).sum()).isEqualTo(1);
        assertThat(kpis.resolvedCount()).isZero();
        assertThat(kpis.averageResolutionHours()).isNull();
        assertThat(kpis.slaCompliance().onTime()).isZero();
        assertThat(kpis.slaCompliance().late()).isZero();
        assertThat(kpis.slaCompliance().rate()).isNull();
    }

    @Test
    void lasProyeccionesParcialesSoloCuentanEnLastEventAt() {
        Instant partialEvent = NOW.minus(Duration.ofMinutes(5));
        rows.partial(RESUELTO, partialEvent).insert();
        rows.created("bache", "Bache", NOW.minus(Duration.ofHours(3))).insert();

        KpisView kpis = service(NOW).kpis(ReportRange.LAST_24H);

        assertThat(kpis.requestsOverTime().points()).extracting(KpisView.Point::count).containsOnly(0L, 1L);
        assertThat(kpis.activeByStatus().get(INGRESADO)).isEqualTo(1L);
        assertThat(kpis.resolvedCount()).isZero();
        assertThat(kpis.lastEventAt()).isEqualTo(partialEvent);
        assertThat(service(NOW).topProcedures(ReportRange.LAST_7D, 5).items())
                .extracting(TopProceduresView.Item::procedureType).containsExactly("bache");
    }

    @Test
    void cuentaLasAltasEnSuTramoConLimitesInclusivosAlInicio() {
        rows.created("bache", "Bache", START_24H).insert();
        rows.created("bache", "Bache", START_24H.plusSeconds(3599)).insert();
        rows.created("bache", "Bache", START_24H.plusSeconds(3600)).insert();
        rows.created("bache", "Bache", NOW).insert();

        KpisView kpis = service(NOW).kpis(ReportRange.LAST_24H);

        assertThat(kpis.requestsOverTime().points().get(0).count()).isEqualTo(2);
        assertThat(kpis.requestsOverTime().points().get(1).count()).isEqualTo(1);
        assertThat(kpis.requestsOverTime().points().get(23).bucketStart()).isEqualTo("2026-11-10T15:00:00Z");
        assertThat(kpis.requestsOverTime().points().get(23).count()).isEqualTo(1);
    }

    @Test
    void laVentanaTerminaEnNowInclusiveEnTodasLasConsultas() {
        Instant future = NOW.plusMillis(1);
        rows.created("bache", "Bache", NOW).insert();
        rows.created("futuro", "Futuro", future).insert();
        rows.created("bache", "Bache", NOW.minus(Duration.ofHours(5)))
                .closed(RESUELTO, NOW, SlaStatus.CUMPLIDO).insert();
        rows.created("bache", "Bache", NOW.minus(Duration.ofHours(5)))
                .closed(RESUELTO, future, SlaStatus.FUERA_DE_PLAZO).insert();

        for (ReportRange range : ReportRange.values()) {
            KpisView kpis = service(NOW).kpis(range);
            assertThat(kpis.requestsOverTime().points().stream().mapToLong(KpisView.Point::count).sum())
                    .isEqualTo(3);
            assertThat(kpis.resolvedCount()).isEqualTo(1);
            assertThat(kpis.slaCompliance().onTime()).isEqualTo(1);
            assertThat(kpis.slaCompliance().late()).isZero();

            assertThat(service(NOW).topProcedures(range, 5).items())
                    .containsExactly(new TopProceduresView.Item("bache", "Bache", 3));
        }
    }

    @Test
    void elRankingUsaElLabelMasRecienteHastaNowSinMirarAltasFuturas() {
        rows.created("bache", "Bache", NOW.minus(Duration.ofHours(1))).insert();
        rows.created("bache", "Bache renombrado", NOW.plusSeconds(1)).insert();

        assertThat(service(NOW).topProcedures(ReportRange.LAST_7D, 5).items())
                .containsExactly(new TopProceduresView.Item("bache", "Bache", 1));
    }

    @Test
    void activeByStatusEsUnaFotoQueNoDependeDeLaVentana() {
        Instant old = NOW.minus(Duration.ofDays(40));
        rows.created("bache", "Bache", old).status(EN_GESTION).insert();
        rows.created("bache", "Bache", old).status(EN_GESTION).insert();
        rows.created("bache", "Bache", old).status(EN_TERRENO).insert();
        rows.created("bache", "Bache", old).closed(CANCELADO, old.plusSeconds(60), null).insert();

        KpisView kpis = service(NOW).kpis(ReportRange.LAST_24H);

        assertThat(kpis.activeByStatus()).containsExactly(
                Map.entry(INGRESADO, 0L), Map.entry(ADMITIDO, 0L), Map.entry(EN_GESTION, 2L), Map.entry(EN_TERRENO, 1L));
    }

    @Test
    void promedioSoloDeResueltosYCumplimientoSinTramitesSinPlazo() {
        Instant created = NOW.minus(Duration.ofDays(3));
        rows.created("bache", "Bache", created)
                .closed(RESUELTO, created.plus(Duration.ofHours(10)), SlaStatus.CUMPLIDO).insert();
        rows.created("bache", "Bache", created)
                .closed(RESUELTO, created.plus(Duration.ofHours(21)), SlaStatus.FUERA_DE_PLAZO).insert();
        // Un rechazo no es una resolución, pero sí cuenta en el cumplimiento.
        rows.created("bache", "Bache", created)
                .closed(RECHAZADO, created.plus(Duration.ofHours(1)), SlaStatus.CUMPLIDO).insert();
        rows.created("bache", "Bache", created)
                .closed(RESUELTO, created.plus(Duration.ofHours(2)), SlaStatus.SIN_PLAZO).insert();
        // Cerrado antes de la ventana.
        rows.created("bache", "Bache", created.minus(Duration.ofDays(10)))
                .closed(RESUELTO, created.minus(Duration.ofDays(9)), SlaStatus.CUMPLIDO).insert();

        KpisView kpis = service(NOW).kpis(ReportRange.LAST_7D);

        assertThat(kpis.resolvedCount()).isEqualTo(3);
        // (10 + 21 + 2) / 3 = 11,0 h.
        assertThat(kpis.averageResolutionHours()).isEqualByComparingTo("11.0");
        assertThat(kpis.slaCompliance().onTime()).isEqualTo(2);
        assertThat(kpis.slaCompliance().late()).isEqualTo(1);
        assertThat(kpis.slaCompliance().rate()).isEqualTo(new BigDecimal("0.6667"));
    }

    @Test
    void elPromedioSeRedondeaAUnDecimal() {
        Instant created = NOW.minus(Duration.ofDays(1));
        rows.created("bache", "Bache", created)
                .closed(RESUELTO, created.plus(Duration.ofMinutes(10 * 60 + 9)), null).insert();
        rows.created("bache", "Bache", created)
                .closed(RESUELTO, created.plus(Duration.ofMinutes(20 * 60)), null).insert();

        // 10 h 9 min y 20 h: 15,075 h, que se redondea a 15,1.
        assertThat(service(NOW).kpis(ReportRange.LAST_7D).averageResolutionHours())
                .isEqualTo(new BigDecimal("15.1"));
    }

    @Test
    void elPromedioUsaInstantesYNoElOffsetGuardado() {
        Instant created = NOW.minus(Duration.ofDays(1));
        // Mismo instante de cierre, 10 h después del alta, guardado con -03:00 y con +05:00.
        rows.created("bache", "Bache", created)
                .closed(RESUELTO, created.plus(Duration.ofHours(10)), null)
                .closedOffset(ZoneOffset.ofHours(-3)).insert();
        rows.created("bache", "Bache", created)
                .closed(RESUELTO, created.plus(Duration.ofHours(10)), null)
                .closedOffset(ZoneOffset.ofHours(5)).insert();

        assertThat(service(NOW).kpis(ReportRange.LAST_7D).averageResolutionHours())
                .isEqualTo(new BigDecimal("10.0"));
    }

    @Test
    void last7dCuentaPorDiaDeChileAunqueCambieElHorario() {
        Instant now = Instant.parse("2026-09-08T15:00:00Z");
        // 00:30 del 7-sep en Chile (UTC-3). Con el desfase de invierno, UTC-4, caería el 6-sep.
        rows.created("bache", "Bache", Instant.parse("2026-09-07T03:30:00Z")).insert();
        // 23:30 del 6-sep en Chile, último minuto del día de 23 horas.
        rows.created("bache", "Bache", Instant.parse("2026-09-07T02:30:00Z")).insert();

        KpisView kpis = service(now).kpis(ReportRange.LAST_7D);

        assertThat(kpis.requestsOverTime().bucket()).isEqualTo("DAY");
        assertThat(kpis.requestsOverTime().points()).hasSize(7);
        assertThat(kpis.requestsOverTime().points().get(4).bucketStart()).isEqualTo("2026-09-06T04:00:00Z");
        assertThat(kpis.requestsOverTime().points().get(4).count()).isEqualTo(1);
        assertThat(kpis.requestsOverTime().points().get(5).bucketStart()).isEqualTo("2026-09-07T03:00:00Z");
        assertThat(kpis.requestsOverTime().points().get(5).count()).isEqualTo(1);
    }

    @Test
    void topOrdenaPorCountYDesempataPorTipoConLimite() {
        Instant at = NOW.minus(Duration.ofDays(1));
        for (int i = 0; i < 3; i++) {
            rows.created("bache", "Bache", at).insert();
        }
        rows.created("alumbrado", "Alumbrado", at).insert();
        rows.created("alumbrado", "Alumbrado", at).insert();
        rows.created("veredas", "Veredas", at).insert();
        rows.created("veredas", "Veredas", at).insert();
        rows.created("arbolado", "Arbolado", at).insert();
        // Fuera de la ventana de 7 días.
        rows.created("arbolado", "Arbolado", NOW.minus(Duration.ofDays(8))).insert();

        TopProceduresView top = service(NOW).topProcedures(ReportRange.LAST_7D, 3);

        assertThat(top.range()).isEqualTo("last7d");
        assertThat(top.items()).containsExactly(
                new TopProceduresView.Item("bache", "Bache", 3),
                new TopProceduresView.Item("alumbrado", "Alumbrado", 2),
                new TopProceduresView.Item("veredas", "Veredas", 2));
    }

    @Test
    void elDesempatePorTipoUsaOrdenBinarioConSignosYDigitos() {
        Instant at = NOW.minus(Duration.ofDays(1));
        for (String type : new String[]{"ab", "a_b", "a1", "a-b"}) {
            rows.created(type, type, at).insert();
        }

        // Binario: '-' (45) < '1' (49) < '_' (95) < 'b' (98).
        assertThat(service(NOW).topProcedures(ReportRange.LAST_7D, 3).items())
                .extracting(TopProceduresView.Item::procedureType)
                .containsExactly("a-b", "a1", "a_b");
    }

    @Test
    void usaElLabelDelAltaMasRecienteDentroDeLaVentana() {
        rows.created("bache", "Bache", NOW.minus(Duration.ofDays(3))).insert();
        rows.created("bache", "Bache en calle", NOW.minus(Duration.ofDays(1))).insert();
        rows.created("bache", "Bache antiguo", NOW.minus(Duration.ofDays(2))).insert();

        assertThat(service(NOW).topProcedures(ReportRange.LAST_7D, 5).items())
                .containsExactly(new TopProceduresView.Item("bache", "Bache en calle", 3));
    }

    @Test
    void conAltasEnElMismoInstanteGanaElLabelMayorSinImportarElOrdenDeCarga() {
        Instant same = NOW.minus(Duration.ofHours(5));
        rows.created("bache", "Bache A", same).insert();
        rows.created("bache", "Bache B", same).insert();
        rows.created("alumbrado", "Luminaria B", same).insert();
        rows.created("alumbrado", "Luminaria A", same).insert();

        assertThat(service(NOW).topProcedures(ReportRange.LAST_7D, 5).items())
                .containsExactly(
                        new TopProceduresView.Item("alumbrado", "Luminaria B", 2),
                        new TopProceduresView.Item("bache", "Bache B", 2));
    }

    @Test
    void elDesempateComparaPorCodigoDeCaracterYNoPorOrdenAlfabetico() {
        // Alfabéticamente ganarían "Zanja" y "Zona"; por código de carácter, á (U+00E1) y a (U+0061) van después de Z.
        Instant same = NOW.minus(Duration.ofHours(5));
        rows.created("veredas", "Zanja", same).insert();
        rows.created("veredas", "árbol", same).insert();
        rows.created("alumbrado", "Zona", same).insert();
        rows.created("alumbrado", "alumbrado", same).insert();

        assertThat(service(NOW).topProcedures(ReportRange.LAST_7D, 5).items())
                .containsExactly(
                        new TopProceduresView.Item("alumbrado", "alumbrado", 2),
                        new TopProceduresView.Item("veredas", "árbol", 2));
    }

    @Test
    void elDesempateDelLabelOrdenaPorCodePointFueraDelPlanoBasico() {
        // Por unidad UTF-16 ganaría U+FF21, porque el emoji empieza con el sustituto U+D83D.
        Instant same = NOW.minus(Duration.ofHours(5));
        rows.created("otro", "Ａ ancho", same).insert();
        rows.created("otro", "😀 emoji", same).insert();

        assertThat(service(NOW).topProcedures(ReportRange.LAST_7D, 5).items())
                .containsExactly(new TopProceduresView.Item("otro", "😀 emoji", 2));
    }

    private KpiQueryService service(Instant now) {
        return new KpiQueryService(queries, Clock.fixed(now, ZoneOffset.UTC));
    }
}
