import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Seed one-shot de report_request_projection. Corre en el contenedor bd-report. */
public class SeedReport {
    static final String[][] TYPES = {
        {"bache", "Bache en calzada"},
        {"alumbrado", "Alumbrado publico"},
        {"agua", "Corte de agua"},
        {"basura", "Retiro de basura"},
        {"ruido", "Ruido molesto"},
        {"poda", "Poda de arboles"}
    };
    static final String[] OPEN = {"INGRESADO", "ADMITIDO", "EN_GESTION", "EN_TERRENO"};
    static final String[] SECTORS = {"Centro", "Norte", "Sur", "Ralco", "Pitril", null};

    public static void main(String[] args) throws Exception {
        String url = System.getenv("ORACLE_URL");
        String user = System.getenv("ORACLE_USER");
        String pass = System.getenv("ORACLE_PASSWORD");
        Instant now = Instant.now();
        int folio = 900000;
        int inserted = 0;
        int skipped = 0;
        List<Object[]> rows = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            rows.add(row(now.minusSeconds(h * 3600L + h * 420L), folio++, h, h % 5 == 0));
        }
        for (int d = 1; d <= 14; d++) {
            for (int n = 0; n < 8; n++) {
                rows.add(row(now.minusSeconds(d * 86400L + n * 7200L), folio++, d + n, (d + n) % 3 == 0));
            }
        }
        for (int d = 15; d < 40; d++) {
            for (int n = 0; n < 3; n++) {
                rows.add(row(now.minusSeconds(d * 86400L + n * 14400L), folio++, d + n, n % 2 == 0));
            }
        }
        String sql = """
                INSERT INTO report_request_projection (
                  request_id, folio, procedure_type, procedure_label, sector, latitude, longitude,
                  status, last_sequence, created_at, sla_business_days, due_date, due_soon_date,
                  sla_status, closed_at, assignee_id, assignee_name, last_event_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (Connection c = DriverManager.getConnection(url, user, pass);
                PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(false);
            for (Object[] r : rows) {
                for (int i = 0; i < r.length; i++) {
                    ps.setObject(i + 1, r[i]);
                }
                try {
                    ps.executeUpdate();
                    inserted++;
                } catch (SQLException e) {
                    if (e.getErrorCode() == 1) {
                        skipped++;
                    } else {
                        throw e;
                    }
                }
            }
            c.commit();
        }
        System.out.println("inserted=" + inserted + " skipped=" + skipped + " total=" + rows.size());
    }

    static Object[] row(Instant created, int folio, int salt, boolean closedFlag) {
        String[] proc = TYPES[salt % TYPES.length];
        String status = closedFlag ? (salt % 2 == 0 ? "RESUELTO" : "RECHAZADO") : OPEN[salt % OPEN.length];
        Instant closed = closedFlag ? created.plusSeconds(3600L * (1 + (salt % 8))) : null;
        String sector = SECTORS[salt % SECTORS.length];
        Double lat = sector == null ? null : (-37.47 + (salt % 20) * 0.01);
        Double lon = sector == null ? null : (-72.35 + (salt % 15) * 0.01);
        String assigneeId = null;
        if (("EN_GESTION".equals(status) || "EN_TERRENO".equals(status) || "RESUELTO".equals(status))
                && salt % 2 == 0) {
            assigneeId = "func-demo-1";
        }
        String assigneeName = assigneeId == null ? null : "Funcionario Demo";
        Date due = Date.valueOf(created.atZone(ZoneOffset.UTC).toLocalDate().plusDays(5));
        Date dueSoon = Date.valueOf(due.toLocalDate().minusDays(1));
        String sla;
        if (closed == null) {
            sla = salt % 4 == 0 ? "VENCIDO" : (salt % 5 == 0 ? "POR_VENCER" : "DENTRO_DE_PLAZO");
        } else if ("RESUELTO".equals(status)) {
            sla = salt % 3 == 0 ? "FUERA_DE_PLAZO" : "CUMPLIDO";
        } else {
            sla = "NO_APLICA";
        }
        String id = UUID.nameUUIDFromBytes(("demo-report-" + folio).getBytes()).toString();
        return new Object[] {
            id,
            String.format("BD-2099-%06d", folio),
            proc[0],
            proc[1],
            sector,
            lat,
            lon,
            status,
            1L,
            Timestamp.from(created),
            5,
            due,
            dueSoon,
            sla,
            closed == null ? null : Timestamp.from(closed),
            assigneeId,
            assigneeName,
            Timestamp.from(closed == null ? created : closed)
        };
    }
}
