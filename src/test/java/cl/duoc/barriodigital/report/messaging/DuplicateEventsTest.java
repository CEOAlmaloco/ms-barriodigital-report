package cl.duoc.barriodigital.report.messaging;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateEventsTest {

    @Test
    void reconoceLaClaveDeOracleConEsquema() {
        assertThat(DuplicateEvents.isDuplicate(violation("ADMIN.PK_PROCESSED_EVENTS"))).isTrue();
    }

    @Test
    void reconoceElNombreQueInformaH2() {
        assertThat(DuplicateEvents.isDuplicate(violation(
                "PUBLIC.PK_PROCESSED_EVENTS ON PUBLIC.PROCESSED_EVENTS(CONSUMER, EVENT_ID) VALUES 1"))).isTrue();
    }

    @Test
    void otraClaveNoEsDuplicado() {
        assertThat(DuplicateEvents.isDuplicate(violation("ADMIN.PK_REPORT_REQUEST_PROJECTION"))).isFalse();
        assertThat(DuplicateEvents.isDuplicate(violation("ADMIN.UK_AUDIT_ENTRIES_EVENT_ID"))).isFalse();
        assertThat(DuplicateEvents.isDuplicate(violation("ADMIN.PK_PROCESSED_EVENTS_OLD"))).isFalse();
    }

    @Test
    void sinNombreBuscaElNombreExactoEnElMensaje() {
        SQLException oracle = new SQLException(
                "ORA-00001: unique constraint (ADMIN.PK_PROCESSED_EVENTS) violated", "23000", 1);
        assertThat(DuplicateEvents.isDuplicate(new DataIntegrityViolationException("x", oracle))).isTrue();

        SQLException otherUnique = new SQLException(
                "ORA-00001: unique constraint (ADMIN.SYS_C0012345) violated on event_id", "23000", 1);
        assertThat(DuplicateEvents.isDuplicate(new DataIntegrityViolationException("x", otherUnique))).isFalse();
    }

    private static DataIntegrityViolationException violation(String constraintName) {
        ConstraintViolationException hibernate = new ConstraintViolationException(
                "could not execute statement", new SQLException("violacion", "23505"), constraintName);
        return new DataIntegrityViolationException("could not execute statement", hibernate);
    }
}
