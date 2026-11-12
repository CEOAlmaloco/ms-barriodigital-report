package cl.duoc.barriodigital.report.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Único lugar donde se usa el calendario. Las fechas son días de Chile, no instantes: el alta y el cierre se
 * convierten a America/Santiago antes de compararlos.
 */
public final class SlaCalculator {

    public static final ZoneId ZONE = ZoneId.of("America/Santiago");

    private final BusinessCalendar calendar;
    private final int dueSoonBusinessDays;

    public SlaCalculator(BusinessCalendar calendar, int dueSoonBusinessDays) {
        if (dueSoonBusinessDays < 0) {
            throw new IllegalArgumentException("due-soon-business-days no puede ser negativo");
        }
        this.calendar = calendar;
        this.dueSoonBusinessDays = dueSoonBusinessDays;
    }

    /**
     * Un alta en día inhábil se entiende hecha el primer día hábil siguiente, y el conteo parte el día hábil
     * siguiente a ese. dueDate es el día hábil número slaBusinessDays.
     */
    public SlaDates dates(Instant createdAt, int slaBusinessDays) {
        LocalDate filed = LocalDate.ofInstant(createdAt, ZONE);
        while (!calendar.isBusinessDay(filed)) {
            filed = filed.plusDays(1);
        }
        LocalDate dueDate = filed;
        for (int counted = 0; counted < slaBusinessDays; ) {
            dueDate = dueDate.plusDays(1);
            if (calendar.isBusinessDay(dueDate)) {
                counted++;
            }
        }
        return new SlaDates(dueDate, dueSoonDate(dueDate));
    }

    /** El día siguiente al día hábil número N + 1 contado hacia atrás, con dueDate como día 1. */
    private LocalDate dueSoonDate(LocalDate dueDate) {
        LocalDate day = dueDate;
        int counted = 1;
        while (counted < dueSoonBusinessDays + 1) {
            day = day.minusDays(1);
            if (calendar.isBusinessDay(day)) {
                counted++;
            }
        }
        return day.plusDays(1);
    }

    public SlaStatus openStatus(SlaDates dates, LocalDate today) {
        if (today.isAfter(dates.dueDate())) {
            return SlaStatus.VENCIDO;
        }
        if (!today.isBefore(dates.dueSoonDate())) {
            return SlaStatus.POR_VENCER;
        }
        return SlaStatus.DENTRO_DE_PLAZO;
    }

    public SlaStatus closedStatus(RequestStatus closedStatus, Instant closedAt, LocalDate dueDate) {
        if (closedStatus == RequestStatus.CANCELADO) {
            return SlaStatus.NO_APLICA;
        }
        return LocalDate.ofInstant(closedAt, ZONE).isAfter(dueDate) ? SlaStatus.FUERA_DE_PLAZO : SlaStatus.CUMPLIDO;
    }

    public record SlaDates(LocalDate dueDate, LocalDate dueSoonDate) {
    }
}
