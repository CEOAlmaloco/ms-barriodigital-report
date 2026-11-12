package cl.duoc.barriodigital.report.service;

import cl.duoc.barriodigital.report.messaging.RequestEventsListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class SlaStartupTest {

    private final SlaMaintenance maintenance = mock(SlaMaintenance.class);
    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    SlaStartupTest() {
        when(registry.getListenerContainer(RequestEventsListener.LISTENER_ID)).thenReturn(container);
        when(maintenance.evaluatePending())
                .thenReturn(new SlaMaintenance.PendingEvaluation(Instant.EPOCH, 0, 0, 0, true));
    }

    @Test
    void evaluaPendientesRecalculaActualizaYRecienEntoncesArrancaElListener() {
        when(maintenance.recalculateOpen()).thenReturn(new SlaMaintenance.Recalculation(Instant.EPOCH, 2, 2, 0, true));
        when(maintenance.refreshStatuses()).thenReturn(true);

        new SlaStartup(maintenance, registry, true).run(new DefaultApplicationArguments());

        InOrder order = inOrder(maintenance, container);
        order.verify(maintenance).evaluatePending();
        order.verify(maintenance).recalculateOpen();
        order.verify(maintenance).refreshStatuses();
        order.verify(container).start();
    }

    @Test
    void unaPreparacionIncompletaQuedaEnElLogYElListenerArrancaIgual(CapturedOutput output) {
        when(maintenance.recalculateOpen()).thenReturn(new SlaMaintenance.Recalculation(Instant.EPOCH, 2, 1, 1, false));
        when(maintenance.refreshStatuses()).thenReturn(false);

        new SlaStartup(maintenance, registry, true).run(new DefaultApplicationArguments());

        verify(container).start();
        assertThat(output).contains("plazos-preparacion-incompleta pendientesCompletos=true"
                + " recalculoCompleto=false fallidos=1 estadoActualizado=false");
    }

    @Test
    void sinAutoStartupNoArrancaElListener() {
        when(maintenance.recalculateOpen()).thenReturn(new SlaMaintenance.Recalculation(Instant.EPOCH, 0, 0, 0, true));
        when(maintenance.refreshStatuses()).thenReturn(true);

        new SlaStartup(maintenance, registry, false).run(new DefaultApplicationArguments());

        verify(container, never()).start();
    }
}
