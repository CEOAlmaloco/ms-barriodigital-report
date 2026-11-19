package cl.duoc.barriodigital.report.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Corre el script real de reparación sobre H2 en modo Oracle, con solo las columnas que usa.
 * Valida la regla (nombre igual al id y fuente válida) y que no toque el orden de la proyección.
 */
class RepairAssigneeNamesScriptTest {

    private Connection db;

    @BeforeEach
    void schema() throws SQLException {
        db = DriverManager.getConnection("jdbc:h2:mem:repair-" + UUID.randomUUID() + ";MODE=Oracle");
        exec("CREATE TABLE staff_member (oid VARCHAR2(64) PRIMARY KEY, name VARCHAR2(200) NOT NULL)");
        exec("CREATE TABLE report_request_projection (request_id VARCHAR2(36) PRIMARY KEY,"
                + " folio VARCHAR2(14), assignee_id VARCHAR2(64), assignee_name VARCHAR2(200),"
                + " assignee_sequence NUMBER(19))");
        exec("INSERT INTO staff_member VALUES ('oid-ana', 'Ana María')");
        exec("INSERT INTO staff_member VALUES ('oid-luis', 'oid-luis')");
        exec("INSERT INTO report_request_projection VALUES ('r-1', 'BD-2026-000001', 'oid-ana', 'oid-ana', 7)");
        exec("INSERT INTO report_request_projection VALUES ('r-2', 'BD-2026-000002', 'oid-luis', 'oid-luis', 3)");
        exec("INSERT INTO report_request_projection VALUES ('r-3', 'BD-2026-000003', 'oid-ana', 'Ana', 2)");
        exec("INSERT INTO report_request_projection VALUES ('r-4', 'BD-2026-000004', NULL, NULL, NULL)");
    }

    @Test
    void reparaSoloLosNombresIgualesAlIdConFuenteValidaSinTocarElOrden() throws Exception {
        runScript();
        runScript();

        assertEquals("Ana María", value("SELECT assignee_name FROM report_request_projection WHERE request_id = 'r-1'"));
        assertEquals("7", value("SELECT assignee_sequence FROM report_request_projection WHERE request_id = 'r-1'"));
        assertEquals("oid-luis", value("SELECT assignee_name FROM report_request_projection WHERE request_id = 'r-2'"));
        assertEquals("Ana", value("SELECT assignee_name FROM report_request_projection WHERE request_id = 'r-3'"));
        assertEquals(null, value("SELECT assignee_name FROM report_request_projection WHERE request_id = 'r-4'"));
    }

    /** WHENEVER SQLERROR es una directiva de SQLcl y SQL*Plus, no SQL: el resto del archivo corre tal cual. */
    private void runScript() throws Exception {
        String script;
        try (var in = new ClassPathResource("db/repair-assignee-names.sql").getInputStream()) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.startsWith("WHENEVER"))
                    .collect(Collectors.joining("\n"));
        }
        ScriptUtils.executeSqlScript(db, new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
    }

    private void exec(String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    private String value(String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
