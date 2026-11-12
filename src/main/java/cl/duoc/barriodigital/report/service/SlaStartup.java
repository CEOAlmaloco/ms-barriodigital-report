package cl.duoc.barriodigital.report.service;

import cl.duoc.barriodigital.report.messaging.RequestEventsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/**
 * Pendientes, recálculo, UPDATE de estado y recién entonces el listener. Un fallo no detiene el listener: congelaría la
 * proyección, y un cierre ya recalcula su fecha antes de evaluarse. Queda en el log y en el health.
 */
@Component
public class SlaStartup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SlaStartup.class);

    private final SlaMaintenance maintenance;
    private final KafkaListenerEndpointRegistry listeners;
    private final boolean listenerAutoStartup;

    public SlaStartup(SlaMaintenance maintenance, KafkaListenerEndpointRegistry listeners,
                      @Value("${barriodigital.kafka.listener.auto-startup:true}") boolean listenerAutoStartup) {
        this.maintenance = maintenance;
        this.listeners = listeners;
        this.listenerAutoStartup = listenerAutoStartup;
    }

    @Override
    public void run(ApplicationArguments args) {
        SlaMaintenance.PendingEvaluation pending = maintenance.evaluatePending();
        SlaMaintenance.Recalculation recalculation = maintenance.recalculateOpen();
        boolean statuses = maintenance.refreshStatuses();
        if (!pending.complete() || !recalculation.complete() || !statuses) {
            log.error("plazos-preparacion-incompleta pendientesCompletos={} recalculoCompleto={} fallidos={}"
                            + " estadoActualizado={}", pending.complete(), recalculation.complete(),
                    pending.failed() + recalculation.failed(), statuses);
        }
        if (listenerAutoStartup) {
            listeners.getListenerContainer(RequestEventsListener.LISTENER_ID).start();
        }
    }
}
