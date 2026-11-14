package cl.duoc.barriodigital.report.service;

import cl.duoc.barriodigital.report.domain.RequestStatus;
import cl.duoc.barriodigital.report.persistence.MapPointQueries;
import cl.duoc.barriodigital.report.persistence.ProjectionRows;
import cl.duoc.barriodigital.report.web.dto.MapPointsView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static cl.duoc.barriodigital.report.domain.RequestStatus.EN_GESTION;
import static cl.duoc.barriodigital.report.domain.RequestStatus.INGRESADO;
import static cl.duoc.barriodigital.report.domain.RequestStatus.RESUELTO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class MapPointsQueryServiceH2Test {

    /** last7d: de 2026-11-04T03:00Z a NOW. */
    private static final Instant NOW = Instant.parse("2026-11-10T15:20:00Z");
    private static final Instant START_7D = Instant.parse("2026-11-04T03:00:00Z");
    private static final Set<RequestStatus> ALL = EnumSet.allOf(RequestStatus.class);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MapPointsQueryService service;

    @SpyBean
    private MapPointQueries queries;

    @Autowired
    private JdbcTemplate jdbc;

    private ProjectionRows rows;

    @BeforeEach
    void clean() {
        rows = new ProjectionRows(jdbc);
        rows.clear();
    }

    @Test
    void sinProyeccionesDevuelveCerosYListasVacias() {
        MapPointsView view = service.mapPoints(ReportRange.LAST_7D, ALL, null);

        assertThat(view.range()).isEqualTo("last7d");
        assertThat(view.items()).isEmpty();
        assertThat(view.locatedTotal()).isZero();
        assertThat(view.withoutLocation()).isZero();
        assertThat(view.bySector()).isEmpty();
        assertThat(view.lastEventAt()).isNull();
    }

    @Test
    void laVentanaIncluyeSusBordesYExcluyeParcialesYAltasFuera() {
        String atStart = located("bache", START_7D, "Centro").insert();
        String atNow = located("bache", NOW, "Centro").insert();
        located("bache", START_7D.minusMillis(1), "Centro").insert();
        located("bache", NOW.plusMillis(1), "Centro").insert();
        rows.partial(EN_GESTION, NOW.minus(Duration.ofHours(1))).location("Centro", -37.99, -71.49).insert();

        MapPointsView view = service.mapPoints(ReportRange.LAST_7D, ALL, null);

        assertThat(view.items()).extracting(MapPointsView.Item::requestId).containsExactly(atNow, atStart);
        assertThat(view.locatedTotal()).isEqualTo(2);
        assertThat(view.withoutLocation()).isZero();
        assertThat(view.bySector()).containsExactly(new MapPointsView.SectorCount("Centro", 2));
    }

    @Test
    void faltaCualquieraDeLasCoordenadasCuentaComoSinUbicacion() {
        Instant created = NOW.minus(Duration.ofDays(1));
        String complete = located("bache", created, "Centro").insert();
        rows.created("bache", "Bache", created).location("Centro", -37.99, null).insert();
        rows.created("bache", "Bache", created).location("Centro", null, -71.49).insert();
        rows.created("bache", "Bache", created).location("Norte", null, null).insert();

        MapPointsView view = service.mapPoints(ReportRange.LAST_7D, ALL, null);

        assertThat(view.items()).extracting(MapPointsView.Item::requestId).containsExactly(complete);
        assertThat(view.locatedTotal()).isEqualTo(1);
        assertThat(view.withoutLocation()).isEqualTo(3);
        assertThat(view.bySector()).containsExactly(
                new MapPointsView.SectorCount("Centro", 3), new MapPointsView.SectorCount("Norte", 1));
    }

    @Test
    void elItemDevuelveLosCamposDelContrato() {
        Instant created = NOW.minus(Duration.ofHours(3));
        String id = rows.created("bache", "Bache en calle", created).status(EN_GESTION)
                .location("Centro", -37.9946, -71.4962).insert();

        MapPointsView.Item item = service.mapPoints(ReportRange.LAST_7D, ALL, null).items().get(0);

        assertThat(item.requestId()).isEqualTo(id);
        assertThat(item.folio()).startsWith("BD-2026-");
        assertThat(item.latitude()).isEqualTo(-37.9946);
        assertThat(item.longitude()).isEqualTo(-71.4962);
        assertThat(item.status()).isEqualTo(EN_GESTION);
        assertThat(item.procedureType()).isEqualTo("bache");
        assertThat(item.procedureLabel()).isEqualTo("Bache en calle");
        assertThat(item.sector()).isEqualTo("Centro");
    }

    @Test
    void losFiltrosDeEstadoYTipoSeCombinanEnPuntosYConteos() {
        Instant created = NOW.minus(Duration.ofDays(2));
        String bacheIngresado = located("bache", created, "Centro").insert();
        String bacheEnGestion = located("bache", created.plusSeconds(1), "Norte").status(EN_GESTION).insert();
        located("bache", created, "Centro").status(RESUELTO).insert();
        located("luminaria", created, "Centro").insert();
        rows.created("bache", "Bache", created).location("Sur", null, null).insert();

        MapPointsView view = service.mapPoints(ReportRange.LAST_7D, EnumSet.of(INGRESADO, EN_GESTION), "bache");

        assertThat(view.items()).extracting(MapPointsView.Item::requestId)
                .containsExactly(bacheEnGestion, bacheIngresado);
        assertThat(view.locatedTotal()).isEqualTo(2);
        assertThat(view.withoutLocation()).isEqualTo(1);
        assertThat(view.bySector()).containsExactly(
                new MapPointsView.SectorCount("Centro", 1),
                new MapPointsView.SectorCount("Norte", 1),
                new MapPointsView.SectorCount("Sur", 1));
    }

    @Test
    void unTipoQueNoExisteDevuelveCeros() {
        located("bache", NOW.minus(Duration.ofDays(1)), "Centro").insert();

        MapPointsView view = service.mapPoints(ReportRange.LAST_7D, ALL, "no_existe");

        assertThat(view.items()).isEmpty();
        assertThat(view.locatedTotal()).isZero();
        assertThat(view.withoutLocation()).isZero();
        assertThat(view.bySector()).isEmpty();
        assertThat(view.lastEventAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
    }

    @Test
    void conAltasEnElMismoInstanteDesempataPorRequestId() {
        Instant created = NOW.minus(Duration.ofHours(5));
        located("bache", created, "Centro").ids("cccccccc-0000-4000-8000-000000000000", "BD-2026-900003").insert();
        located("bache", created, "Centro").ids("aaaaaaaa-0000-4000-8000-000000000000", "BD-2026-900001").insert();
        located("bache", created, "Centro").ids("bbbbbbbb-0000-4000-8000-000000000000", "BD-2026-900002").insert();

        MapPointsView view = service.mapPoints(ReportRange.LAST_7D, ALL, null);

        assertThat(view.items()).extracting(MapPointsView.Item::requestId).containsExactly(
                "aaaaaaaa-0000-4000-8000-000000000000",
                "bbbbbbbb-0000-4000-8000-000000000000",
                "cccccccc-0000-4000-8000-000000000000");
    }

    @Test
    void elTopeDejaFueraLasAltasMasAntiguasSinAfectarTotalesNiSectores() {
        Instant newest = NOW.minus(Duration.ofMinutes(1));
        List<String> ids = new ArrayList<>();
        int located = MapPointsQueryService.MAX_POINTS + 2;
        for (int i = 0; i < located; i++) {
            String sector = i % 2 == 0 ? "Centro" : "Norte";
            ids.add(located("bache", newest.minusSeconds(i), sector).insert());
        }
        rows.created("bache", "Bache", newest).location("Sur", null, null).insert();

        MapPointsView view = service.mapPoints(ReportRange.LAST_7D, ALL, null);

        assertThat(view.items()).hasSize(MapPointsQueryService.MAX_POINTS);
        assertThat(view.items()).extracting(MapPointsView.Item::requestId)
                .containsExactlyElementsOf(ids.subList(0, MapPointsQueryService.MAX_POINTS));
        assertThat(view.locatedTotal()).isEqualTo(located);
        assertThat(view.withoutLocation()).isEqualTo(1);
        assertThat(view.bySector()).containsExactly(
                new MapPointsView.SectorCount("Centro", 501),
                new MapPointsView.SectorCount("Norte", 501),
                new MapPointsView.SectorCount("Sur", 1));
        assertThat(view.bySector().stream().mapToLong(MapPointsView.SectorCount::count).sum())
                .isEqualTo(view.locatedTotal() + view.withoutLocation());
    }

    @Test
    void sectoresPorConteoDescendenteYCodePointConElSinSectorAlFinal() {
        Instant created = NOW.minus(Duration.ofDays(1));
        for (int i = 0; i < 3; i++) {
            rows.created("bache", "Bache", created).location(null, null, null).insert();
        }
        located("bache", created, "Sur").insert();
        located("bache", created, "Sur").insert();
        located("bache", created, "Norte").insert();
        located("bache", created, "Centro").insert();
        located("bache", created, "Ñuble").insert();

        MapPointsView view = service.mapPoints(ReportRange.LAST_7D, ALL, null);

        assertThat(view.bySector()).containsExactly(
                new MapPointsView.SectorCount("Sur", 2),
                new MapPointsView.SectorCount("Centro", 1),
                new MapPointsView.SectorCount("Norte", 1),
                new MapPointsView.SectorCount("Ñuble", 1),
                new MapPointsView.SectorCount(null, 3));
    }

    @Test
    void lastEventAtIncluyeTramitesFueraDeLosFiltros() {
        Instant lastEvent = NOW.minus(Duration.ofMinutes(2));
        located("bache", NOW.minus(Duration.ofDays(1)), "Centro").insert();
        rows.partial(EN_GESTION, lastEvent).insert();

        assertThat(service.mapPoints(ReportRange.LAST_24H, EnumSet.of(RESUELTO), null).lastEventAt())
                .isEqualTo(lastEvent);
    }

    @Test
    void lasConsultasCorrenEnUnaTransaccionSerializableDeSoloLectura() {
        AtomicReference<Integer> isolation = new AtomicReference<>();
        AtomicReference<Boolean> readOnly = new AtomicReference<>();
        doAnswer(invocation -> {
            isolation.set(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            readOnly.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return invocation.callRealMethod();
        }).when(queries).sectorCounts(any());

        service.mapPoints(ReportRange.LAST_7D, ALL, null);

        assertThat(isolation.get()).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
        assertThat(readOnly.get()).isTrue();
    }

    private ProjectionRows.Row located(String procedureType, Instant createdAt, String sector) {
        return rows.created(procedureType, "Tipo " + procedureType, createdAt).location(sector, -37.99, -71.49);
    }
}
