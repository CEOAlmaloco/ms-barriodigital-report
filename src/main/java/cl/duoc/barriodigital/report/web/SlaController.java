package cl.duoc.barriodigital.report.web;

import cl.duoc.barriodigital.report.domain.SlaStatus;
import cl.duoc.barriodigital.report.service.SlaQueryService;
import cl.duoc.barriodigital.report.web.dto.SlaAlertsView;
import cl.duoc.barriodigital.report.web.dto.SlaBatchView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Mismo criterio que KpiController: los parámetros llegan como texto y se validan después del rol. */
@RestController
@RequestMapping("/api/report/sla")
public class SlaController {

    static final Set<String> ALLOWED_ROLES = Set.of(ReportCaller.FUNCIONARIO, ReportCaller.ADMIN, ReportCaller.AUDITOR);
    static final String STATUS_MESSAGE = "status solo acepta VENCIDO y POR_VENCER";
    static final String STATUS_REPEATED_MESSAGE = "status tiene valores repetidos";
    static final String ASSIGNEE_MESSAGE = "assignee debe ser me o none";
    static final String PAGE_MESSAGE = "page debe ser 0 o mayor";
    static final String SIZE_MESSAGE = "size debe estar entre 1 y 50";
    static final String REQUEST_IDS_MESSAGE = "requestIds debe tener entre 1 y 50 UUID sin repetir";
    static final int MAX_SIZE = 50;
    static final int MAX_REQUEST_IDS = 50;

    private static final Set<SlaStatus> ALERTS = EnumSet.of(SlaStatus.VENCIDO, SlaStatus.POR_VENCER);
    private static final Pattern PAGE_FORMAT = Pattern.compile("\\d{1,9}");
    private static final Pattern SIZE_FORMAT = Pattern.compile("\\d{1,2}");
    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final SlaQueryService slaQueryService;

    public SlaController(SlaQueryService slaQueryService) {
        this.slaQueryService = slaQueryService;
    }

    @GetMapping
    public SlaAlertsView alerts(
            @RequestHeader(value = ReportCaller.HEADER_USER_ROLES, required = false) String roles,
            @RequestHeader(value = ReportCaller.HEADER_USER_ID, required = false) String userId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "assignee", required = false) String assignee,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "size", required = false) String size
    ) {
        String role = ReportCaller.requireRole(roles, ALLOWED_ROLES);
        return slaQueryService.alerts(statuses(status), assignee(assignee), userId,
                ReportCaller.FUNCIONARIO.equals(role), page(page), size(size));
    }

    @GetMapping("/batch")
    public SlaBatchView batch(
            @RequestHeader(value = ReportCaller.HEADER_USER_ROLES, required = false) String roles,
            @RequestParam(value = "requestIds", required = false) String requestIds
    ) {
        ReportCaller.requireRole(roles, ALLOWED_ROLES);
        return slaQueryService.batch(requestIds(requestIds));
    }

    private static Set<SlaStatus> statuses(String value) {
        if (value == null) {
            return ALERTS;
        }
        Set<SlaStatus> statuses = EnumSet.noneOf(SlaStatus.class);
        boolean repeated = false;
        for (String part : value.split(",", -1)) {
            SlaStatus status = ALERTS.stream().filter(s -> s.name().equals(part)).findFirst()
                    .orElseThrow(() -> badRequest(STATUS_MESSAGE));
            repeated |= !statuses.add(status);
        }
        if (repeated) {
            throw badRequest(STATUS_REPEATED_MESSAGE);
        }
        return statuses;
    }

    private static SlaQueryService.Assignee assignee(String value) {
        if (value == null) {
            return SlaQueryService.Assignee.ANY;
        }
        return switch (value) {
            case "me" -> SlaQueryService.Assignee.ME;
            case "none" -> SlaQueryService.Assignee.NONE;
            default -> throw badRequest(ASSIGNEE_MESSAGE);
        };
    }

    private static int page(String value) {
        if (value == null) {
            return 0;
        }
        if (!PAGE_FORMAT.matcher(value).matches()) {
            throw badRequest(PAGE_MESSAGE);
        }
        return Integer.parseInt(value);
    }

    private static int size(String value) {
        if (value == null) {
            return 20;
        }
        if (!SIZE_FORMAT.matcher(value).matches()) {
            throw badRequest(SIZE_MESSAGE);
        }
        int parsed = Integer.parseInt(value);
        if (parsed < 1 || parsed > MAX_SIZE) {
            throw badRequest(SIZE_MESSAGE);
        }
        return parsed;
    }

    /** requests guarda el id en minúsculas: un UUID en mayúsculas se normaliza, y cuenta como repetido. */
    private static List<String> requestIds(String value) {
        if (value == null || value.isEmpty()) {
            throw badRequest(REQUEST_IDS_MESSAGE);
        }
        String[] parts = value.split(",", -1);
        if (parts.length > MAX_REQUEST_IDS) {
            throw badRequest(REQUEST_IDS_MESSAGE);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String part : parts) {
            if (!UUID_FORMAT.matcher(part).matches() || !ids.add(part.toLowerCase(Locale.ROOT))) {
                throw badRequest(REQUEST_IDS_MESSAGE);
            }
        }
        return new ArrayList<>(ids);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
