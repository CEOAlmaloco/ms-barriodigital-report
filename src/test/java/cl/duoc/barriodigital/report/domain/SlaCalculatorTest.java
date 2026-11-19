package cl.duoc.barriodigital.report.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class SlaCalculatorTest {

    private static final Set<LocalDate> HOLIDAYS = Set.of(
            LocalDate.parse("2026-10-12"), LocalDate.parse("2026-12-25"), LocalDate.parse("2027-01-01"));

    private final SlaCalculator calculator = new SlaCalculator(new BusinessCalendar(HOLIDAYS, Set.of(2026, 2027)), 2);

    @Test
    void altaEnDiaHabilCuentaDesdeElDiaHabilSiguiente() {
        // Viernes 6-nov-2026 a las 10:00 en Chile: día 1 el lunes 9, vence el viernes 13.
        SlaCalculator.SlaDates dates = calculator.dates(Instant.parse("2026-11-06T13:00:00Z"), 5);

        assertThat(dates.dueDate()).isEqualTo("2026-11-13");
        assertThat(dates.dueSoonDate()).isEqualTo("2026-11-12");
    }

    @Test
    void altaEnSabadoSeEntiendeHechaElLunes() {
        SlaCalculator.SlaDates dates = calculator.dates(Instant.parse("2026-11-07T15:00:00Z"), 5);

        assertThat(dates.dueDate()).isEqualTo("2026-11-16");
    }

    @Test
    void elDiaDelAltaSeTomaEnChileYNoEnUtc() {
        // 01:00 UTC del sábado 7 es el viernes 6 a las 22:00 en Chile.
        assertThat(calculator.dates(Instant.parse("2026-11-07T01:00:00Z"), 5).dueDate()).isEqualTo("2026-11-13");
    }

    @Test
    void altaEnFeriadoSeEntiendeHechaElDiaHabilSiguiente() {
        // Lunes 12-oct es feriado: se entiende hecha el martes 13 y el día 1 es el miércoles 14.
        assertThat(calculator.dates(Instant.parse("2026-10-12T14:00:00Z"), 1).dueDate()).isEqualTo("2026-10-14");
    }

    @Test
    void saltaLosFeriadosDelConteo() {
        // Jueves 24-dic: el 25 es feriado, así que los días son lunes 28, martes 29 y miércoles 30.
        assertThat(calculator.dates(Instant.parse("2026-12-24T14:00:00Z"), 3).dueDate()).isEqualTo("2026-12-30");
    }

    @Test
    void elConteoCruzaElAnoConLosFeriadosDelSiguiente() {
        // Miércoles 30-dic: jueves 31 (el 31 no es feriado), viernes 1-ene feriado, lunes 4 y martes 5.
        assertThat(calculator.dates(Instant.parse("2026-12-30T14:00:00Z"), 3).dueDate()).isEqualTo("2027-01-05");
    }

    @Test
    void unAnoSinCalendarioSeCuentaSoloConFinesDeSemanaYAvisaUnaVez(CapturedOutput output) {
        BusinessCalendar without2027 = new BusinessCalendar(HOLIDAYS, Set.of(2026));
        SlaCalculator partialCalendar = new SlaCalculator(without2027, 2);

        // El 1-ene-2027 es feriado, pero 2027 no está cubierto: se cuenta como hábil y la fecha se adelanta.
        assertThat(partialCalendar.dates(Instant.parse("2026-12-31T14:00:00Z"), 1).dueDate()).isEqualTo("2027-01-01");
        partialCalendar.dates(Instant.parse("2026-12-30T14:00:00Z"), 10);

        assertThat(without2027.missingYears()).containsExactly(2027);
        assertThat(output.getOut().split("calendario-feriados-incompleto year=2027", -1)).hasSize(2);
    }

    @Test
    void porVencerEmpiezaConDosDiasHabilesORestantes() {
        // Vence el martes 17: los días 1, 2 y 3 hacia atrás son martes, lunes y viernes, y el aviso empieza el sábado.
        SlaCalculator.SlaDates dates = calculator.dates(Instant.parse("2026-11-09T13:00:00Z"), 6);
        assertThat(dates.dueDate()).isEqualTo("2026-11-17");
        assertThat(dates.dueSoonDate()).isEqualTo("2026-11-14");

        assertThat(calculator.openStatus(dates, LocalDate.parse("2026-11-13"))).isEqualTo(SlaStatus.DENTRO_DE_PLAZO);
        assertThat(calculator.openStatus(dates, LocalDate.parse("2026-11-14"))).isEqualTo(SlaStatus.POR_VENCER);
        assertThat(calculator.openStatus(dates, LocalDate.parse("2026-11-17"))).isEqualTo(SlaStatus.POR_VENCER);
        assertThat(calculator.openStatus(dates, LocalDate.parse("2026-11-18"))).isEqualTo(SlaStatus.VENCIDO);
    }

    @Test
    void unPlazoDeUnDiaNacePorVencer() {
        SlaCalculator.SlaDates dates = calculator.dates(Instant.parse("2026-11-09T13:00:00Z"), 1);

        assertThat(dates.dueDate()).isEqualTo("2026-11-10");
        assertThat(calculator.openStatus(dates, LocalDate.parse("2026-11-09"))).isEqualTo(SlaStatus.POR_VENCER);
    }

    @Test
    void elCierreSeEvaluaPorLaFechaEnChile() {
        LocalDate due = LocalDate.parse("2026-11-13");

        // 02:30 UTC del sábado 14 todavía es viernes 13 en Chile.
        assertThat(calculator.closedStatus(RequestStatus.RESUELTO, Instant.parse("2026-11-14T02:30:00Z"), due))
                .isEqualTo(SlaStatus.CUMPLIDO);
        assertThat(calculator.closedStatus(RequestStatus.RECHAZADO, Instant.parse("2026-11-14T03:30:00Z"), due))
                .isEqualTo(SlaStatus.FUERA_DE_PLAZO);
        assertThat(calculator.closedStatus(RequestStatus.CANCELADO, Instant.parse("2026-11-20T12:00:00Z"), due))
                .isEqualTo(SlaStatus.NO_APLICA);
    }
}
