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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static cl.duoc.barriodigital.report.domain.RequestStatus.EN_GESTION;
import static cl.duoc.barriodigital.report.domain.RequestStatus.RESUELTO;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SlaControllerTest {

    private static final String SLA = "/api/report/sla";
    private static final String BATCH = "/api/report/sla/batch";
    private static final Instant CREATED = Instant.parse("2026-11-02T13:00:00Z");
    private static final String FUNCIONARIO_ID = "func-1";

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
    @ValueSource(strings = {"Funcionario", "Admin", "Auditor"})
    void elStaffVeAmbosEndpoints(String role) throws Exception {
        mvc.perform(as(get(SLA), role)).andExpect(status().isOk());
        mvc.perform(as(get(BATCH).param("requestIds", UUID.randomUUID().toString()), role))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Vecino", "Admin,Funcionario", "Otro", ""})
    void elVecinoYLosRolesAmbiguosReciben403(String role) throws Exception {
        expectError(mvc.perform(as(get(SLA), role)), 403, ReportCaller.FORBIDDEN_MESSAGE, SLA);
        expectError(mvc.perform(as(get(BATCH).param("requestIds", "x"), role)), 403,
                ReportCaller.FORBIDDEN_MESSAGE, BATCH);
    }

    @Test
    void elRolSeRevisaAntesQueLosParametros() throws Exception {
        expectError(mvc.perform(as(get(SLA).param("status", "CUMPLIDO"), "Vecino")), 403,
                ReportCaller.FORBIDDEN_MESSAGE, SLA);
    }

    @Test
    void listaSoloAbiertosConAlertaOrdenadosPorVencimientoYFolio() throws Exception {
        alert("BD-2026-000003", "2026-11-09", SlaStatus.VENCIDO, null);
        alert("BD-2026-000002", "2026-11-09", SlaStatus.VENCIDO, null);
        alert("BD-2026-000001", "2026-11-12", SlaStatus.POR_VENCER, FUNCIONARIO_ID);
        alert("BD-2026-000004", "2026-11-20", SlaStatus.DENTRO_DE_PLAZO, null);
        rows.created("bache", "Bache", CREATED).sla(5, LocalDate.parse("2026-11-09"), null, null)
                .closed(RESUELTO, Instant.parse("2026-11-10T13:00:00Z"), SlaStatus.FUERA_DE_PLAZO).insert();
        rows.partial(EN_GESTION, CREATED).slaStatus(SlaStatus.VENCIDO).insert();

        mvc.perform(as(get(SLA), "Admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].folio")
                        .value(contains("BD-2026-000002", "BD-2026-000003", "BD-2026-000001")))
                .andExpect(jsonPath("$.content[0].dueDate").value("2026-11-09"))
                .andExpect(jsonPath("$.content[0].slaStatus").value("VENCIDO"))
                .andExpect(jsonPath("$.content[0].procedureLabel").value("Bache"))
                .andExpect(jsonPath("$.content[2].assigneeId").value(FUNCIONARIO_ID))
                .andExpect(jsonPath("$.content[2].assigneeName").value("Ana"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.lastEventAt").isNotEmpty());

        mvc.perform(as(get(SLA).param("status", "VENCIDO"), "Admin"))
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    void paginaConLasReglasDelListadoDeRequests() throws Exception {
        for (int i = 1; i <= 5; i++) {
            alert(String.format("BD-2026-%06d", i), "2026-11-09", SlaStatus.VENCIDO, null);
        }

        mvc.perform(as(get(SLA).param("page", "1").param("size", "2"), "Auditor"))
                .andExpect(jsonPath("$.content[*].folio").value(contains("BD-2026-000003", "BD-2026-000004")))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void assigneeMeFiltraPorElFuncionarioQueLlama() throws Exception {
        alert("BD-2026-000001", "2026-11-09", SlaStatus.VENCIDO, FUNCIONARIO_ID);
        alert("BD-2026-000002", "2026-11-09", SlaStatus.VENCIDO, "func-2");

        mvc.perform(asUser(get(SLA).param("assignee", "me"), "Funcionario", FUNCIONARIO_ID))
                .andExpect(jsonPath("$.content[*].folio").value(contains("BD-2026-000001")));
    }

    @Test
    void assigneeMeDelAdminYDelAuditorEsUnaListaVacia() throws Exception {
        alert("BD-2026-000001", "2026-11-09", SlaStatus.VENCIDO, "admin-1");

        for (String role : new String[]{"Admin", "Auditor"}) {
            mvc.perform(asUser(get(SLA).param("assignee", "me"), role, "admin-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(0)))
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    @Test
    void assigneeMeSinXUserIdNuncaListaSinFiltro() throws Exception {
        alert("BD-2026-000001", "2026-11-09", SlaStatus.VENCIDO, FUNCIONARIO_ID);
        alert("BD-2026-000002", "2026-11-09", SlaStatus.VENCIDO, null);

        mvc.perform(as(get(SLA).param("assignee", "me"), "Funcionario"))
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(asUser(get(SLA).param("assignee", "me"), "Funcionario", " "))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void assigneeNoneListaLosSinResponsable() throws Exception {
        alert("BD-2026-000001", "2026-11-09", SlaStatus.VENCIDO, FUNCIONARIO_ID);
        alert("BD-2026-000002", "2026-11-09", SlaStatus.VENCIDO, null);

        mvc.perform(as(get(SLA).param("assignee", "none"), "Admin"))
                .andExpect(jsonPath("$.content[*].folio").value(contains("BD-2026-000002")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "CUMPLIDO", "vencido", "VENCIDO,", "VENCIDO, POR_VENCER", "DENTRO_DE_PLAZO"})
    void statusInvalidoResponde400(String value) throws Exception {
        expectError(mvc.perform(as(get(SLA).param("status", value), "Admin")), 400, SlaController.STATUS_MESSAGE, SLA);
    }

    @Test
    void statusRepetidoResponde400() throws Exception {
        expectError(mvc.perform(as(get(SLA).param("status", "VENCIDO,VENCIDO"), "Admin")), 400,
                SlaController.STATUS_REPEATED_MESSAGE, SLA);
    }

    @Test
    void assigneePageYSizeInvalidosResponden400() throws Exception {
        expectError(mvc.perform(as(get(SLA).param("assignee", "otro"), "Admin")), 400,
                SlaController.ASSIGNEE_MESSAGE, SLA);
        for (String page : new String[]{"-1", "abc", ""}) {
            expectError(mvc.perform(as(get(SLA).param("page", page), "Admin")), 400, SlaController.PAGE_MESSAGE, SLA);
        }
        for (String size : new String[]{"0", "51", "abc", ""}) {
            expectError(mvc.perform(as(get(SLA).param("size", size), "Admin")), 400, SlaController.SIZE_MESSAGE, SLA);
        }
    }

    @Test
    void elLoteDevuelveUnItemPorIdEnElMismoOrden() throws Exception {
        String due = alert("BD-2026-000001", "2026-11-12", SlaStatus.POR_VENCER, null);
        String withoutSla = rows.created("bache", "Bache", CREATED).sla(null, null, null, SlaStatus.SIN_PLAZO).insert();
        String partial = rows.partial(EN_GESTION, CREATED).insert();
        String unknown = UUID.randomUUID().toString();

        mvc.perform(as(get(BATCH).param("requestIds", String.join(",", unknown, due, partial, withoutSla)), "Funcionario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].requestId").value(contains(unknown, due, partial, withoutSla)))
                .andExpect(jsonPath("$.items[0].slaStatus").isEmpty())
                .andExpect(jsonPath("$.items[0].dueDate").isEmpty())
                .andExpect(jsonPath("$.items[1].slaStatus").value("POR_VENCER"))
                .andExpect(jsonPath("$.items[1].dueDate").value("2026-11-12"))
                .andExpect(jsonPath("$.items[2].slaStatus").isEmpty())
                .andExpect(jsonPath("$.items[3].slaStatus").value("SIN_PLAZO"))
                .andExpect(jsonPath("$.items[3].dueDate").isEmpty())
                .andExpect(jsonPath("$.lastEventAt").isNotEmpty());
    }

    @Test
    void elLoteNormalizaMayusculas() throws Exception {
        String id = alert("BD-2026-000001", "2026-11-12", SlaStatus.POR_VENCER, null);

        mvc.perform(as(get(BATCH).param("requestIds", id.toUpperCase()), "Admin"))
                .andExpect(jsonPath("$.items[0].requestId").value(id))
                .andExpect(jsonPath("$.items[0].slaStatus").value("POR_VENCER"));
    }

    @Test
    void elLoteAceptaHasta50Ids() throws Exception {
        mvc.perform(as(get(BATCH).param("requestIds", ids(50)), "Admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(50)));
    }

    @Test
    void elLoteRechazaVacioMasDe50RepetidosYNoUuid() throws Exception {
        String id = UUID.randomUUID().toString();
        String[] invalid = {"", ids(51), id + "," + id, id + "," + id.toUpperCase(), "1-1-1-1-1", id + ","};
        for (String value : invalid) {
            expectError(mvc.perform(as(get(BATCH).param("requestIds", value), "Admin")), 400,
                    SlaController.REQUEST_IDS_MESSAGE, BATCH);
        }
        expectError(mvc.perform(as(get(BATCH), "Admin")), 400, SlaController.REQUEST_IDS_MESSAGE, BATCH);
    }

    private String alert(String folio, String dueDate, SlaStatus sla, String assigneeId) {
        LocalDate due = LocalDate.parse(dueDate);
        return rows.created("bache", "Bache", CREATED)
                .ids(UUID.randomUUID().toString(), folio)
                .sla(5, due, due.minusDays(1), sla)
                .assignee(assigneeId, assigneeId == null ? null : "Ana")
                .insert();
    }

    private static String ids(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID().toString()).collect(Collectors.joining(","));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role) {
        return request.header(ReportCaller.HEADER_USER_ROLES, role);
    }

    private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request, String role,
                                                        String userId) {
        return as(request, role).header(ReportCaller.HEADER_USER_ID, userId);
    }

    private static void expectError(ResultActions result, int code, String message, String path) throws Exception {
        result.andExpect(status().is(code))
                .andExpect(jsonPath("$.status").value(code))
                .andExpect(jsonPath("$.error").isNotEmpty())
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value(path));
    }
}
