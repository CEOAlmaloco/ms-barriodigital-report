package cl.duoc.barriodigital.report.web;

import cl.duoc.barriodigital.report.service.KpiQueryService;
import cl.duoc.barriodigital.report.service.ReportRange;
import cl.duoc.barriodigital.report.web.dto.KpisView;
import cl.duoc.barriodigital.report.web.dto.TopProceduresView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Los parámetros llegan como texto y se validan después del rol: con tipos, Spring respondería 400 antes de
 * llegar aquí y se rompería el orden 403 antes que 400.
 */
@RestController
@RequestMapping("/api/report")
public class KpiController {

    static final Set<String> ALLOWED_ROLES = Set.of(ReportCaller.ADMIN, ReportCaller.AUDITOR);
    static final String RANGE_MESSAGE = "range debe ser last24h o last7d";
    static final String LIMIT_MESSAGE = "limit debe estar entre 1 y 20";
    private static final Pattern LIMIT_FORMAT = Pattern.compile("\\d{1,2}");
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final KpiQueryService kpiQueryService;

    public KpiController(KpiQueryService kpiQueryService) {
        this.kpiQueryService = kpiQueryService;
    }

    @GetMapping("/kpis")
    public KpisView kpis(
            @RequestHeader(value = ReportCaller.HEADER_USER_ROLES, required = false) String roles,
            @RequestParam(value = "range", required = false) String range
    ) {
        ReportCaller.requireRole(roles, ALLOWED_ROLES);
        return kpiQueryService.kpis(range(range, ReportRange.LAST_24H));
    }

    @GetMapping("/top-procedures")
    public TopProceduresView topProcedures(
            @RequestHeader(value = ReportCaller.HEADER_USER_ROLES, required = false) String roles,
            @RequestParam(value = "range", required = false) String range,
            @RequestParam(value = "limit", required = false) String limit
    ) {
        ReportCaller.requireRole(roles, ALLOWED_ROLES);
        ReportRange parsedRange = range(range, ReportRange.LAST_7D);
        return kpiQueryService.topProcedures(parsedRange, limit(limit));
    }

    static ReportRange range(String value, ReportRange defaultRange) {
        if (value == null) {
            return defaultRange;
        }
        return ReportRange.fromValue(value)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, RANGE_MESSAGE));
    }

    private static int limit(String value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        if (!LIMIT_FORMAT.matcher(value).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, LIMIT_MESSAGE);
        }
        int parsed = Integer.parseInt(value);
        if (parsed < 1 || parsed > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, LIMIT_MESSAGE);
        }
        return parsed;
    }
}
