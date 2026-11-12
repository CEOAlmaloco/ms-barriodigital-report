package cl.duoc.barriodigital.report.service;

import cl.duoc.barriodigital.report.domain.ProcessedEvent;
import cl.duoc.barriodigital.report.persistence.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProcessedEventsCleanupH2Test {

    private static final Instant NOW = Instant.parse("2026-12-15T06:30:00Z");

    @Autowired
    private ProcessedEventRepository processedEvents;

    @BeforeEach
    void clean() {
        processedEvents.deleteAllInBatch();
    }

    @Test
    void borraSoloLasMarcasAntiguasDeReport() {
        save("report", "a0000000-0000-4000-8000-000000000001", NOW.minus(Duration.ofDays(31)));
        save("report", "a0000000-0000-4000-8000-000000000002", NOW.minus(Duration.ofDays(30)).plusSeconds(1));
        save("otro-consumidor", "a0000000-0000-4000-8000-000000000003", NOW.minus(Duration.ofDays(90)));

        int deleted = new ProcessedEventsCleanup(processedEvents, Clock.fixed(NOW, ZoneOffset.UTC)).purgeOld();

        assertThat(deleted).isEqualTo(1);
        assertThat(processedEvents.findAll())
                .extracting(ProcessedEvent::getEventId)
                .containsExactlyInAnyOrder("a0000000-0000-4000-8000-000000000002",
                        "a0000000-0000-4000-8000-000000000003");
    }

    @Test
    void programadaALas0330EnChile() {
        assertThat(ProcessedEventsCleanup.CRON).isEqualTo("0 30 3 * * *");
        assertThat(ProcessedEventsCleanup.ZONE).isEqualTo("America/Santiago");
    }

    private void save(String consumer, String eventId, Instant processedAt) {
        processedEvents.save(new ProcessedEvent(consumer, eventId, processedAt));
    }
}
