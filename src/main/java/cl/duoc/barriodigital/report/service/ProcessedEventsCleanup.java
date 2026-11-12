package cl.duoc.barriodigital.report.service;

import cl.duoc.barriodigital.report.domain.ProcessedEvent;
import cl.duoc.barriodigital.report.persistence.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Borra a diario las marcas de report con más de 30 días, el plazo en que aún puede llegar un duplicado.
 * Filtra por consumidor: la tabla vive en un esquema compartido.
 */
@Component
public class ProcessedEventsCleanup {

    public static final String CRON = "0 30 3 * * *";
    public static final String ZONE = "America/Santiago";
    static final Duration RETENTION = Duration.ofDays(30);

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventsCleanup.class);

    private final ProcessedEventRepository processedEvents;
    private final Clock clock;

    public ProcessedEventsCleanup(ProcessedEventRepository processedEvents, Clock clock) {
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    @Scheduled(cron = CRON, zone = ZONE)
    public int purgeOld() {
        Instant limit = clock.instant().minus(RETENTION);
        int deleted = processedEvents.deleteByConsumerProcessedBefore(ProcessedEvent.REPORT_CONSUMER, limit);
        log.info("processed-events-limpieza consumidor={} eliminados={} procesadosAntesDe={}",
                ProcessedEvent.REPORT_CONSUMER, deleted, limit);
        return deleted;
    }
}
