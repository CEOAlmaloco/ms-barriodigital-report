package cl.duoc.barriodigital.report.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class HolidaysPropertiesTest {

    @Autowired
    private HolidaysProperties holidays;

    @Test
    void cargaLosFeriadosNacionalesDe2026Y2027() {
        assertThat(holidays.years()).containsOnlyKeys(2026, 2027);
        assertThat(holidays.years().get(2026)).hasSize(16);
        assertThat(holidays.years().get(2027)).hasSize(17);
        assertThat(holidays.dates()).contains(
                LocalDate.parse("2026-06-29"), LocalDate.parse("2026-10-12"),
                LocalDate.parse("2027-06-28"), LocalDate.parse("2027-09-17"), LocalDate.parse("2027-10-11"));
    }

    @Test
    void noIncluyeElFeriadoBancarioNiLosRegionales() {
        assertThat(holidays.dates()).doesNotContain(
                LocalDate.parse("2026-12-31"), LocalDate.parse("2027-12-31"),
                LocalDate.parse("2026-06-07"), LocalDate.parse("2027-06-07"),
                LocalDate.parse("2026-08-20"), LocalDate.parse("2027-08-20"));
    }

    @Test
    void losTrasladosDeLaLey19668CaenEnLunes() {
        assertThat(List.of("2026-06-29", "2026-10-12", "2027-06-28", "2027-10-11"))
                .allSatisfy(date -> assertThat(LocalDate.parse(date).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY));
    }

    @Test
    void rechazaUnFeriadoBajoOtroAnoOUnoRepetido() {
        HolidaysProperties.Holiday newYear = new HolidaysProperties.Holiday(LocalDate.parse("2026-01-01"), "Año Nuevo");

        assertThatThrownBy(() -> new HolidaysProperties(Map.of(2027, List.of(newYear))))
                .hasMessageContaining("está bajo el año 2027");
        assertThatThrownBy(() -> new HolidaysProperties(Map.of(2026, List.of(newYear, newYear))))
                .hasMessageContaining("está repetido");
    }
}
