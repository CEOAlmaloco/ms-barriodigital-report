package cl.duoc.barriodigital.report.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SlaHealthTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void elGrupoSlaMuestraElCalendarioSinAutorizacion() throws Exception {
        mvc.perform(get("/actuator/health/sla"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.sla.details.coveredYears").value(contains(2026, 2027)))
                .andExpect(jsonPath("$.components.sla.details.missingYears").isArray())
                .andExpect(jsonPath("$.components.sla.details.startupPendingEvaluation.complete").value(true))
                .andExpect(jsonPath("$.components.sla.details.startupRecalculation.complete").value(true))
                .andExpect(jsonPath("$.components.sla.details.lastStatusRefresh.ok").value(true));
    }

    @Test
    void elHealthGeneralSigueSinDetalleSinAutorizacion() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
