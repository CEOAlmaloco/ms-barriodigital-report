package cl.duoc.barriodigital.report.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Días hábiles: ni sábado, ni domingo, ni feriado. Un año que el calendario no cubre se cuenta solo con fines de
 * semana: adelanta la fecha, así que el aviso no llega tarde, pero un cierre de ese tramo puede evaluarse fuera de
 * plazo. Por eso se registra y se expone en el health.
 */
public final class BusinessCalendar {

    private static final Logger log = LoggerFactory.getLogger(BusinessCalendar.class);

    private final Set<LocalDate> holidays;
    private final SortedSet<Integer> coveredYears;
    private final Set<Integer> missingYears = new ConcurrentSkipListSet<>();

    public BusinessCalendar(Set<LocalDate> holidays, Set<Integer> coveredYears) {
        this.holidays = Set.copyOf(holidays);
        this.coveredYears = new TreeSet<>(coveredYears);
    }

    public boolean isBusinessDay(LocalDate day) {
        DayOfWeek weekday = day.getDayOfWeek();
        if (weekday == DayOfWeek.SATURDAY || weekday == DayOfWeek.SUNDAY) {
            return false;
        }
        if (!coveredYears.contains(day.getYear())) {
            if (missingYears.add(day.getYear())) {
                log.warn("calendario-feriados-incompleto year={}", day.getYear());
            }
            return true;
        }
        return !holidays.contains(day);
    }

    public SortedSet<Integer> coveredYears() {
        return new TreeSet<>(coveredYears);
    }

    /** Años que algún cálculo necesitó y el calendario no tenía, desde el arranque. */
    public SortedSet<Integer> missingYears() {
        return new TreeSet<>(missingYears);
    }
}
