package cl.duoc.barriodigital.report.web;

import cl.duoc.barriodigital.report.domain.RequestStatus;
import cl.duoc.barriodigital.report.service.MapPointsQueryService;
import cl.duoc.barriodigital.report.service.ReportRange;
import cl.duoc.barriodigital.report.web.dto.MapPointsView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Mismo criterio que KpiController: los parámetros llegan como texto y se validan después del rol. */
@RestController
@RequestMapping("/api/report")
public class MapPointsController {

    static final Set<String> ALLOWED_ROLES = Set.of(ReportCaller.FUNCIONARIO, ReportCaller.ADMIN, ReportCaller.AUDITOR);
    static final String STATUS_MESSAGE = "status no es valido";
    static final String STATUS_REPEATED_MESSAGE = "status tiene valores repetidos";
    static final String PROCEDURE_TYPE_MESSAGE = "procedureType no es valido";

    /** Formato de los códigos del catálogo. */
    private static final Pattern PROCEDURE_TYPE_FORMAT = Pattern.compile("[a-z0-9_-]{1,40}");

    private final MapPointsQueryService mapPointsQueryService;

    public MapPointsController(MapPointsQueryService mapPointsQueryService) {
        this.mapPointsQueryService = mapPointsQueryService;
    }

    @GetMapping("/map-points")
    public MapPointsView mapPoints(
            @RequestHeader(value = ReportCaller.HEADER_USER_ROLES, required = false) String roles,
            @RequestParam(value = "range", required = false) String range,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "procedureType", required = false) String procedureType
    ) {
        ReportCaller.requireRole(roles, ALLOWED_ROLES);
        return mapPointsQueryService.mapPoints(KpiController.range(range, ReportRange.LAST_7D),
                statuses(status), procedureType(procedureType));
    }

    private static Set<RequestStatus> statuses(String value) {
        if (value == null) {
            return EnumSet.allOf(RequestStatus.class);
        }
        Set<RequestStatus> statuses = EnumSet.noneOf(RequestStatus.class);
        boolean repeated = false;
        for (String part : value.split(",", -1)) {
            RequestStatus parsed = statusOf(part).orElseThrow(() -> badRequest(STATUS_MESSAGE));
            repeated |= !statuses.add(parsed);
        }
        if (repeated) {
            throw badRequest(STATUS_REPEATED_MESSAGE);
        }
        return statuses;
    }

    private static Optional<RequestStatus> statusOf(String value) {
        return Arrays.stream(RequestStatus.values()).filter(s -> s.name().equals(value)).findFirst();
    }

    private static String procedureType(String value) {
        if (value == null) {
            return null;
        }
        if (!PROCEDURE_TYPE_FORMAT.matcher(value).matches()) {
            throw badRequest(PROCEDURE_TYPE_MESSAGE);
        }
        return value;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
