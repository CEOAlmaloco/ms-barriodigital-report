package cl.duoc.barriodigital.report.web.dto;

import cl.duoc.barriodigital.report.domain.SlaStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Misma forma de página que el listado de requests, más lastEventAt. */
public record SlaAlertsView(
        List<Item> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        Instant lastEventAt
) {

    public record Item(
            String requestId,
            String folio,
            String procedureType,
            String procedureLabel,
            String assigneeId,
            String assigneeName,
            LocalDate dueDate,
            SlaStatus slaStatus
    ) {
    }
}
