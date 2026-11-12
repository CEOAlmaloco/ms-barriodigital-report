package cl.duoc.barriodigital.report.web.dto;

import java.time.Instant;
import java.util.List;

public record TopProceduresView(String range, List<Item> items, Instant lastEventAt) {

    public record Item(String procedureType, String procedureLabel, long count) {
    }
}
