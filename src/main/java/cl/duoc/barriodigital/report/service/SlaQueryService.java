package cl.duoc.barriodigital.report.service;

import cl.duoc.barriodigital.report.domain.RequestProjection;
import cl.duoc.barriodigital.report.domain.SlaStatus;
import cl.duoc.barriodigital.report.persistence.KpiQueries;
import cl.duoc.barriodigital.report.persistence.RequestProjectionRepository;
import cl.duoc.barriodigital.report.web.dto.SlaAlertsView;
import cl.duoc.barriodigital.report.web.dto.SlaBatchView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class SlaQueryService {

    public enum Assignee { ANY, ME, NONE }

    private final RequestProjectionRepository projections;
    private final KpiQueries kpiQueries;

    public SlaQueryService(RequestProjectionRepository projections, KpiQueries kpiQueries) {
        this.projections = projections;
        this.kpiQueries = kpiQueries;
    }

    /**
     * assignee=me sin un responsable posible (Admin, Auditor o sin X-User-Id) devuelve una página vacía sin
     * consultar, para que nunca se convierta en un listado sin filtro.
     */
    public SlaAlertsView alerts(Set<SlaStatus> statuses, Assignee assignee, String userId, boolean canBeAssignee,
                                int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        Page<RequestProjection> result = switch (assignee) {
            case ANY -> projections.findOpenAlerts(statuses, request);
            case NONE -> projections.findOpenAlertsWithoutAssignee(statuses, request);
            case ME -> canBeAssignee && userId != null && !userId.isBlank()
                    ? projections.findOpenAlertsByAssignee(statuses, userId, request)
                    : new PageImpl<>(List.of(), request, 0);
        };
        List<SlaAlertsView.Item> content = result.getContent().stream()
                .map(p -> new SlaAlertsView.Item(p.getRequestId(), p.getFolio(), p.getProcedureType(),
                        p.getProcedureLabel(), p.getAssigneeId(), p.getAssigneeName(), p.getDueDate(), p.getSlaStatus()))
                .toList();
        return new SlaAlertsView(content, page, size, result.getTotalElements(), result.getTotalPages(),
                kpiQueries.lastEventAt());
    }

    /** Un ítem por id pedido, en el mismo orden. Lo que report no conoce, o solo conoce como parcial, va en null. */
    public SlaBatchView batch(List<String> requestIds) {
        Map<String, RequestProjection> found = projections.findAllById(requestIds).stream()
                .collect(Collectors.toMap(RequestProjection::getRequestId, Function.identity()));
        List<SlaBatchView.Item> items = requestIds.stream()
                .map(id -> {
                    RequestProjection projection = found.get(id);
                    return projection == null || projection.isPartial()
                            ? new SlaBatchView.Item(id, null, null)
                            : new SlaBatchView.Item(id, projection.getSlaStatus(), projection.getDueDate());
                })
                .toList();
        return new SlaBatchView(items, kpiQueries.lastEventAt());
    }
}
