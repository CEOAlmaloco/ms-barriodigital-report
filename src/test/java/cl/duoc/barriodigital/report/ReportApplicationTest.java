package cl.duoc.barriodigital.report;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arranque del contexto con H2, sin Oracle ni Kafka reales.
 */
@SpringBootTest
class ReportApplicationTest {

    @Autowired
    private Environment environment;

    @Test
    void elContextoArranca() {
        assertThat(environment.getProperty("server.port")).isEqualTo("8086");
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("ms-barriodigital-report");
        assertThat(environment.getProperty("spring.kafka.admin.auto-create")).isEqualTo("false");
    }
}
