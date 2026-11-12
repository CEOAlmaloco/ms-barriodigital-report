package cl.duoc.barriodigital.report.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Detiene el arranque si la clave primaria de processed_events no se llama pk_processed_events.
 * El duplicado se reconoce solo por ese nombre: si Hibernate creó la tabla antes que db/report-tables.sql,
 * la clave queda como SYS_C... y cada duplicado terminaría en la DLT.
 * Corre antes que el listener, que se inicia al final del refresh del contexto.
 */
@Component
@DependsOnDatabaseInitialization
public class ProcessedEventsSchemaCheck implements InitializingBean {

    static final String TABLE = "PROCESSED_EVENTS";
    static final String PRIMARY_KEY = "PK_PROCESSED_EVENTS";

    private final DataSource dataSource;

    public ProcessedEventsSchemaCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        String found = primaryKeyName();
        if (!PRIMARY_KEY.equalsIgnoreCase(found)) {
            throw new IllegalStateException("processed_events debe tener la clave primaria pk_processed_events"
                    + " (encontrada: " + (found == null ? "ninguna" : found) + "). Aplica db/report-tables.sql");
        }
    }

    private String primaryKeyName() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet keys = connection.getMetaData().getPrimaryKeys(null, connection.getSchema(), TABLE)) {
            return keys.next() ? keys.getString("PK_NAME") : null;
        }
    }
}
