package cl.duoc.barriodigital.report.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessedEventsSchemaCheckTest {

    @Test
    void conLaClaveDelContratoArranca() {
        DriverManagerDataSource dataSource = h2();
        new JdbcTemplate(dataSource).execute("CREATE TABLE processed_events (consumer VARCHAR2(40) NOT NULL,"
                + " event_id VARCHAR2(36) NOT NULL, CONSTRAINT pk_processed_events PRIMARY KEY (consumer, event_id))");

        assertThatCode(() -> new ProcessedEventsSchemaCheck(dataSource).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void conLaClaveQueNombraHibernateNoArranca() {
        DriverManagerDataSource dataSource = h2();
        new JdbcTemplate(dataSource).execute("CREATE TABLE processed_events (consumer VARCHAR2(40) NOT NULL,"
                + " event_id VARCHAR2(36) NOT NULL, CONSTRAINT sys_c0012345 PRIMARY KEY (consumer, event_id))");

        assertThatThrownBy(() -> new ProcessedEventsSchemaCheck(dataSource).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SYS_C0012345")
                .hasMessageContaining("db/report-tables.sql");
    }

    @Test
    void sinTablaNoArranca() {
        assertThatThrownBy(() -> new ProcessedEventsSchemaCheck(h2()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ninguna");
    }

    private static DriverManagerDataSource h2() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:check-" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
    }
}
