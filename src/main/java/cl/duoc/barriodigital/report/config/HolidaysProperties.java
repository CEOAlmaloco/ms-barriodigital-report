package cl.duoc.barriodigital.report.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * holidays-cl.yml. Un archivo mal formado impide arrancar; uno desactualizado no, porque el año que falta se
 * informa en el health.
 */
@Validated
@ConfigurationProperties(prefix = "barriodigital.holidays")
public record HolidaysProperties(@NotEmpty Map<Integer, List<@Valid Holiday>> years) {

    public HolidaysProperties {
        Set<LocalDate> seen = new HashSet<>();
        if (years != null) {
            years.forEach((year, holidays) -> holidays.forEach(holiday -> {
                if (holiday.date() != null && holiday.date().getYear() != year) {
                    throw new IllegalArgumentException("El feriado " + holiday.date() + " está bajo el año " + year);
                }
                if (holiday.date() != null && !seen.add(holiday.date())) {
                    throw new IllegalArgumentException("El feriado " + holiday.date() + " está repetido");
                }
            }));
        }
    }

    public Set<LocalDate> dates() {
        Set<LocalDate> dates = new HashSet<>();
        years.values().forEach(holidays -> holidays.forEach(holiday -> dates.add(holiday.date())));
        return dates;
    }

    public record Holiday(@NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          @NotBlank String name) {
    }
}
