package cl.duoc.barriodigital.report.web;

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

import static cl.duoc.barriodigital.report.domain.RequestStatus.EN_GESTION;
import static cl.duoc.barriodigital.report.domain.RequestStatus.RESUELTO;
import static cl.duoc.barriodigital.report.domain.SlaStatus.CUMPLIDO;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MapPointsControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-10T15:20:00Z");
    private static final String MAP = "/api/report/map-points";

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
    @ValueSource(strings = {"Funcionario", "Admin", "Auditor", "ROLE_Funcionario", "auditor", "Admin,Otro"})
    void funcionarioAdminYAuditorVenElMapa(String role) throws Exception {
        mvc.perform(as(get(MAP), role)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Vecino", "Funcionario,Admin", "ROLE_Admin,Auditor", "Otro", " "})
    void rolNoPermitidoOAmbiguoResponde403(String role) throws Exception {
        expectError(mvc.perform(as(get(MAP), role)), 403, ReportCaller.FORBIDDEN_MESSAGE);
    }

    @Test
    void sinHeaderDeRolResponde403() throws Exception {
        expectError(mvc.perform(get(MAP)), 403, ReportCaller.FORBIDDEN_MESSAGE);
    }

    @Test
    void elRolSeRevisaAntesQueLosParametros() throws Exception {
        expectError(mvc.perform(as(get(MAP).param("status", "X").param("procedureType", "Mal"), "Vecino")),
                403, ReportCaller.FORBIDDEN_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "xyz", "LAST7D", "last30d"})
    void rangeInvalidoResponde400(String range) throws Exception {
        expectError(mvc.perform(as(get(MAP).param("range", range), "Admin")), 400, KpiController.RANGE_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "X", "ingresado", "VENCIDO", "INGRESADO,", ",INGRESADO", "INGRESADO, RESUELTO"})
    void statusInvalidoResponde400(String value) throws Exception {
        expectError(mvc.perform(as(get(MAP).param("status", value), "Funcionario")),
                400, MapPointsController.STATUS_MESSAGE);
    }

    @Test
    void statusRepetidoResponde400() throws Exception {
        expectError(mvc.perform(as(get(MAP).param("status", "INGRESADO,EN_GESTION,INGRESADO"), "Admin")),
                400, MapPointsController.STATUS_REPEATED_MESSAGE);
    }

    @Test
    void unStatusDesconocidoGanaAlRepetido() throws Exception {
        expectError(mvc.perform(as(get(MAP).param("status", "INGRESADO,INGRESADO,X"), "Admin")),
                400, MapPointsController.STATUS_MESSAGE);
    }

    @Test
    void aceptaLosSieteEstados() throws Exception {
        mvc.perform(as(get(MAP).param("status",
                        "INGRESADO,ADMITIDO,EN_GESTION,EN_TERRENO,RESUELTO,RECHAZADO,CANCELADO"), "Auditor"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Bache", "bache luz", "bache,luz", "bache.luz", "ñandu",
            "a2345678901234567890123456789012345678901"})
    void procedureTypeInvalidoResponde400(String value) throws Exception {
        expectError(mvc.perform(as(get(MAP).param("procedureType", value), "Admin")),
                400, MapPointsController.PROCEDURE_TYPE_MESSAGE);
    }

    @Test
    void procedureTypeEnElLimiteDelFormatoEsValido() throws Exception {
        mvc.perform(as(get(MAP).param("procedureType", "a234567890123456789012345678901234567_-9"), "Admin"))
                .andExpect(status().isOk());
    }

    @Test
    void porDefectoUsaLast7dYSerializaVaciosYNulos() throws Exception {
        mvc.perform(as(get(MAP), "Funcionario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("last7d"))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.locatedTotal").value(0))
                .andExpect(jsonPath("$.withoutLocation").value(0))
                .andExpect(jsonPath("$.bySector", hasSize(0)))
                .andExpect(jsonPath("$.lastEventAt").hasJsonPath())
                .andExpect(jsonPath("$.lastEventAt").isEmpty());
    }

    @Test
    void conDatosDevuelveLaFormaDelContrato() throws Exception {
        Instant created = NOW.minus(Duration.ofHours(2));
        String id = rows.created("bache", "Bache en calle", created).status(EN_GESTION)
                .location("Centro", -37.9946, -71.4962).insert();
        rows.created("bache", "Bache en calle", created).location("Centro", null, null).insert();
        rows.created("bache", "Bache en calle", created).location(null, null, null).insert();
        rows.created("luminaria", "Luminaria", created).location("Norte", -37.98, -71.48).insert();
        rows.created("bache", "Bache en calle", created).location("Norte", -37.98, -71.48)
                .closed(RESUELTO, NOW.minus(Duration.ofMinutes(5)), CUMPLIDO).insert();

        mvc.perform(as(get(MAP).param("range", "last24h").param("status", "INGRESADO", "EN_GESTION")
                        .param("procedureType", "bache"), "Funcionario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("last24h"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].requestId").value(id))
                .andExpect(jsonPath("$.items[0].folio").isNotEmpty())
                .andExpect(jsonPath("$.items[0].latitude").value(-37.9946))
                .andExpect(jsonPath("$.items[0].longitude").value(-71.4962))
                .andExpect(jsonPath("$.items[0].status").value("EN_GESTION"))
                .andExpect(jsonPath("$.items[0].procedureType").value("bache"))
                .andExpect(jsonPath("$.items[0].procedureLabel").value("Bache en calle"))
                .andExpect(jsonPath("$.items[0].sector").value("Centro"))
                .andExpect(jsonPath("$.items[0].assigneeId").doesNotExist())
                .andExpect(jsonPath("$.locatedTotal").value(1))
                .andExpect(jsonPath("$.withoutLocation").value(2))
                .andExpect(jsonPath("$.bySector", hasSize(2)))
                .andExpect(jsonPath("$.bySector[0].sector").value("Centro"))
                .andExpect(jsonPath("$.bySector[0].count").value(2))
                .andExpect(jsonPath("$.bySector[1].sector").hasJsonPath())
                .andExpect(jsonPath("$.bySector[1].sector").isEmpty())
                .andExpect(jsonPath("$.bySector[1].count").value(1))
                .andExpect(jsonPath("$.lastEventAt").value("2026-11-10T15:15:00Z"));
    }

    @Test
    void otroMetodoResponde405ConElMismoCuerpoDeError() throws Exception {
        expectError(mvc.perform(as(post(MAP), "Admin")), 405, "Method Not Allowed");
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role) {
        return request.header(ReportCaller.HEADER_USER_ROLES, role);
    }

    private static void expectError(ResultActions result, int code, String message) throws Exception {
        result.andExpect(status().is(code))
                .andExpect(jsonPath("$.status").value(code))
                .andExpect(jsonPath("$.error").isNotEmpty())
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value(MAP));
    }
}
