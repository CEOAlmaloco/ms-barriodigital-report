package cl.duoc.barriodigital.report.service;

import cl.duoc.barriodigital.report.domain.ProcessedEvent;
import cl.duoc.barriodigital.report.domain.RequestEvent;
import cl.duoc.barriodigital.report.domain.RequestProjection;
import cl.duoc.barriodigital.report.persistence.ProcessedEventRepository;
import cl.duoc.barriodigital.report.persistence.RequestProjectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Aplica cada evento a la proyección por trámite. No conoce Kafka. */
@Service
public class KpiService {

    private final ProcessedEventRepository processedEvents;
    private final RequestProjectionRepository projections;
    private final Clock clock;

    public KpiService(ProcessedEventRepository processedEvents, RequestProjectionRepository projections, Clock clock) {
        this.processedEvents = processedEvents;
        this.projections = projections;
        this.clock = clock;
    }

    /**
     * La marca va primero, en la misma transacción: un duplicado falla antes de tocar la proyección y el rollback
     * deja todo igual. El duplicado lo reconoce quien llama, después del rollback.
     */
    @Transactional
    public void apply(RequestEvent event) {
        processedEvents.saveAndFlush(new ProcessedEvent(
                ProcessedEvent.REPORT_CONSUMER, event.eventId().toString(), clock.instant()));
        RequestProjection projection = projections.findByIdForUpdate(event.requestId())
                .orElseGet(() -> RequestProjection.empty(event.requestId(), event.folio()));
        projection.apply(event);
        projections.saveAndFlush(projection);
    }
}
