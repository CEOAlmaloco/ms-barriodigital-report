package cl.duoc.barriodigital.report.web;

import cl.duoc.barriodigital.report.domain.SlaStatus;
import cl.duoc.barriodigital.report.persistence.ProjectionRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static cl.duoc.barriodigital.report.domain.RequestStatus.RESUELTO;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class KpiControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-10T15:20:00Z");
    private static final String KPIS = "/api/report/kpis";
    private static final String TOP = "/api/report/top-procedures";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ProjectionRows rows;

    @BeforeEach
    void clean() {
        rows = new ProjectionRows(jdbc);
        rows.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Admin", "Auditor", "ROLE_Admin", "auditor", "Admin,Otro"})
    void adminYAuditorVenAmbosEndpoints(String role) throws Exception {
        mvc.perform(as(get(KPIS), role)).andExpect(status().isOk());
        mvc.perform(as(get(TOP), role)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Vecino", "Funcionario", "Admin,Auditor", "ROLE_Admin,Funcionario", "Otro", " "})
    void rolNoPermitidoOAmbiguoResponde403(String role) throws Exception {
        expectError(mvc.perform(as(get(KPIS), role)), 403, ReportCaller.FORBIDDEN_MESSAGE, KPIS);
        expectError(mvc.perform(as(get(TOP), role)), 403, ReportCaller.FORBIDDEN_MESSAGE, TOP);
    }

    @Test
    void sinHeaderDeRolResponde403() throws Exception {
        expectError(mvc.perform(get(KPIS)), 403, ReportCaller.FORBIDDEN_MESSAGE, KPIS);
        expectError(mvc.perform(get(TOP)), 403, ReportCaller.FORBIDDEN_MESSAGE, TOP);
    }

    @Test
    void elRolSeRevisaAntesQueLosParametros() throws Exception {
        expectError(mvc.perform(as(get(KPIS).param("range", "xyz"), "Vecino")),
                403, ReportCaller.FORBIDDEN_MESSAGE, KPIS);
        expectError(mvc.perform(as(get(TOP).param("limit", "abc"), "Funcionario")),
                403, ReportCaller.FORBIDDEN_MESSAGE, TOP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "xyz", "LAST24H", "last30d"})
    void rangeInvalidoResponde400(String range) throws Exception {
        expectError(mvc.perform(as(get(KPIS).param("range", range), "Admin")), 400, KpiController.RANGE_MESSAGE, KPIS);
        expectError(mvc.perform(as(get(TOP).param("range", range), "Admin")), 400, KpiController.RANGE_MESSAGE, TOP);
    }

    @Test
    void rangeRepetidoResponde400() throws Exception {
        expectError(mvc.perform(as(get(KPIS).param("range", "last24h", "last7d"), "Admin")),
                400, KpiController.RANGE_MESSAGE, KPIS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "21", "-1", "abc", "", "1.5", "100"})
    void limitFueraDeRangoResponde400(String limit) throws Exception {
        expectError(mvc.perform(as(get(TOP).param("limit", limit), "Auditor")), 400, KpiController.LIMIT_MESSAGE, TOP);
    }

    @Test
    void limitEnLosBordesEsValido() throws Exception {
        mvc.perform(as(get(TOP).param("limit", "1"), "Admin")).andExpect(status().isOk());
        mvc.perform(as(get(TOP).param("limit", "20"), "Admin")).andExpect(status().isOk());
    }

    @Test
    void kpisPorDefectoUsaLast24hYSerializaLosNulos() throws Exception {
        mvc.perform(as(get(KPIS), "Admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("last24h"))
                .andExpect(jsonPath("$.requestsOverTime.bucket").value("HOUR"))
                .andExpect(jsonPath("$.requestsOverTime.points", hasSize(24)))
                .andExpect(jsonPath("$.requestsOverTime.points[0].bucketStart").value("2026-11-09T16:00:00Z"))
                .andExpect(jsonPath("$.requestsOverTime.points[0].count").value(0))
                .andExpect(jsonPath("$.activeByStatus.INGRESADO").value(0))
                .andExpect(jsonPath("$.activeByStatus.ADMITIDO").value(0))
                .andExpect(jsonPath("$.activeByStatus.EN_GESTION").value(0))
                .andExpect(jsonPath("$.activeByStatus.EN_TERRENO").value(0))
                .andExpect(jsonPath("$.averageResolutionHours").isEmpty())
                .andExpect(jsonPath("$.averageResolutionHours").hasJsonPath())
                .andExpect(jsonPath("$.resolvedCount").value(0))
                .andExpect(jsonPath("$.slaCompliance.rate").hasJsonPath())
                .andExpect(jsonPath("$.slaCompliance.rate").isEmpty())
                .andExpect(jsonPath("$.slaCompliance.onTime").value(0))
                .andExpect(jsonPath("$.slaCompliance.late").value(0))
                .andExpect(jsonPath("$.lastEventAt").hasJsonPath())
                .andExpect(jsonPath("$.lastEventAt").isEmpty());
    }

    @Test
    void kpisConDatosDevuelveLosValoresDelContrato() throws Exception {
        Instant created = NOW.minus(Duration.ofHours(12));
        Instant lastEvent = NOW.minus(Duration.ofMinutes(1));
        rows.created("bache", "Bache", created)
                .closed(RESUELTO, created.plus(Duration.ofHours(10)), SlaStatus.CUMPLIDO)
                .lastEventAt(lastEvent).insert();
        rows.created("bache", "Bache", created).insert();

        mvc.perform(as(get(KPIS).param("range", "last7d"), "Auditor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("last7d"))
                .andExpect(jsonPath("$.requestsOverTime.bucket").value("DAY"))
                .andExpect(jsonPath("$.requestsOverTime.points", hasSize(7)))
                .andExpect(jsonPath("$.requestsOverTime.points[6].bucketStart").value("2026-11-10T03:00:00Z"))
                .andExpect(jsonPath("$.requestsOverTime.points[6].count").value(2))
                .andExpect(jsonPath("$.activeByStatus.INGRESADO").value(1))
                .andExpect(jsonPath("$.averageResolutionHours").value(10.0))
                .andExpect(jsonPath("$.resolvedCount").value(1))
                .andExpect(jsonPath("$.slaCompliance.rate").value(1.0))
                .andExpect(jsonPath("$.slaCompliance.onTime").value(1))
                .andExpect(jsonPath("$.slaCompliance.late").value(0))
                .andExpect(jsonPath("$.lastEventAt").value("2026-11-10T15:19:00Z"));
    }

    @Test
    void topProceduresPorDefectoUsaLast7dYLimite5() throws Exception {
        String[] types = {"t1", "t2", "t3", "t4", "t5", "t6"};
        for (String type : types) {
            rows.created(type, "Tipo " + type, NOW.minus(Duration.ofDays(2))).insert();
        }

        mvc.perform(as(get(TOP), "Admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("last7d"))
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[0].procedureType").value("t1"))
                .andExpect(jsonPath("$.items[0].procedureLabel").value("Tipo t1"))
                .andExpect(jsonPath("$.items[0].count").value(1))
                .andExpect(jsonPath("$.lastEventAt").value("2026-11-08T15:20:00Z"));
    }

    @Test
    void rutaInexistenteUsaElMismoCuerpoDeError() throws Exception {
        expectError(mvc.perform(as(get("/api/report/otra"), "Admin")), 404, "Not Found", "/api/report/otra");
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role) {
        return request.header(ReportCaller.HEADER_USER_ROLES, role);
    }

    private static void expectError(ResultActions result, int code, String message, String path) throws Exception {
        result.andExpect(status().is(code))
                .andExpect(jsonPath("$.status").value(code))
                .andExpect(jsonPath("$.error").isNotEmpty())
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value(path));
    }
}
