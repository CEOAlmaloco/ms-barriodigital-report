package cl.duoc.barriodigital.report.messaging;

import cl.duoc.barriodigital.report.service.KpiService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Un hilo por partición. Valida, convierte y delega en KpiService. El offset se confirma al retornar
 * (AckMode.RECORD), después del commit en Oracle. Lo arranca SlaStartup, después del recálculo de plazos.
 */
@Component
public class RequestEventsListener {

    public static final String LISTENER_ID = "report-requests-events";
    public static final String GROUP = "report-group";
    static final String CONSUMER = "report";

    private static final Logger log = LoggerFactory.getLogger(RequestEventsListener.class);

    private final RequestEventMapper mapper;
    private final KpiService kpiService;

    public RequestEventsListener(RequestEventMapper mapper, KpiService kpiService) {
        this.mapper = mapper;
        this.kpiService = kpiService;
    }

    @KafkaListener(
            id = LISTENER_ID,
            idIsGroup = false,
            groupId = GROUP,
            topics = "${barriodigital.kafka.topic.requests-events}",
            concurrency = "3",
            clientIdPrefix = CONSUMER,
            autoStartup = "false")
    public void onMessage(ConsumerRecord<String, String> record) {
        switch (mapper.map(record.value())) {
            case MappedEvent.Unknown unknown -> log.warn(
                    "evento-desconocido topic={} consumidor={} eventType={} eventId={}",
                    record.topic(), CONSUMER, unknown.eventType(), unknown.eventId());
            case MappedEvent.Known known -> {
                try {
                    kpiService.apply(known.event());
                } catch (DataIntegrityViolationException ex) {
                    if (!DuplicateEvents.isDuplicate(ex)) {
                        throw ex;
                    }
                    log.info("duplicado topic={} consumidor={} eventId={}",
                            record.topic(), CONSUMER, known.event().eventId());
                }
            }
        }
    }
}
