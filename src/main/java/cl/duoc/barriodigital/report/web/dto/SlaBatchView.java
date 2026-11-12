package cl.duoc.barriodigital.report.web.dto;

import cl.duoc.barriodigital.report.domain.SlaStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SlaBatchView(List<Item> items, Instant lastEventAt) {

    public record Item(String requestId, SlaStatus slaStatus, LocalDate dueDate) {
    }
}
