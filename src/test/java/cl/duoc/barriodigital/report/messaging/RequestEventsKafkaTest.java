package cl.duoc.barriodigital.report.messaging;

import cl.duoc.barriodigital.report.domain.RequestEvent;
import cl.duoc.barriodigital.report.persistence.RequestProjectionRepository;
import cl.duoc.barriodigital.report.service.KpiService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.ByteBuffer;
import java.time.Duration;

import static cl.duoc.barriodigital.report.messaging.KafkaTestSupport.committedOffset;
import static cl.duoc.barriodigital.report.messaging.KafkaTestSupport.findByKey;
import static cl.duoc.barriodigital.report.messaging.KafkaTestSupport.header;
import static cl.duoc.barriodigital.report.messaging.KafkaTestSupport.send;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.DLT;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.TOPIC;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.created;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.newRequestId;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.rated;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "barriodigital.kafka.listener.auto-startup=true"})
@EmbeddedKafka(partitions = 3, topics = {TOPIC, DLT})
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class RequestEventsKafkaTest {

    private static final Duration WAIT = Duration.ofSeconds(45);

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private RequestProjectionRepository projections;

    @SpyBean
    private KpiService kpiService;

    @Test
    void eventoValidoSeProyectaYConfirmaElOffset() throws Exception {
        String requestId = newRequestId();
        RecordMetadata sent = send(broker, TOPIC, requestId, created(requestId, 1, "2026-11-10T13:00:00Z", 5)
                .toString());

        await().atMost(WAIT).until(() -> projections.findById(requestId).isPresent());
        await().atMost(WAIT).until(() ->
                committedOffset(broker, RequestEventsListener.GROUP, TOPIC, sent.partition()) > sent.offset());
    }

    @Test
    void malformadoVaALaDltSinReintentos(CapturedOutput output) throws Exception {
        String requestId = newRequestId();
        ObjectNode event = created(requestId, 1, "2026-11-10T13:00:00Z", 5);
        ((ObjectNode) event.get("payload")).putNull("longitude");
        RecordMetadata sent = send(broker, TOPIC, requestId, event.toString());

        ConsumerRecord<String, String> dead = findByKey(broker, DLT, requestId, WAIT).orElseThrow();

        assertThat(dead.value()).isEqualTo(event.toString());
        assertThat(dead.partition()).isEqualTo(sent.partition());
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(TOPIC);
        assertThat(header(dead, "kafka_dlt-original-consumer-group")).isEqualTo(RequestEventsListener.GROUP);
        assertThat(header(dead, "kafka_dlt-exception-message")).contains("latitude y longitude");
        assertThat(ByteBuffer.wrap(dead.headers().lastHeader("kafka_dlt-original-offset").value()).getLong())
                .isEqualTo(sent.offset());
        await().atMost(WAIT).until(() ->
                committedOffset(broker, RequestEventsListener.GROUP, TOPIC, sent.partition()) > sent.offset());
        verify(kpiService, never()).apply(argThat(e -> e != null && requestId.equals(e.requestId())));
        await().atMost(WAIT).until(() -> output.getOut().contains("evento-muerto topic=requests.events partition="
                + sent.partition() + " offset=" + sent.offset() + " eventId=" + event.get("eventId").asText()
                + " motivo=\"Payload invalido: latitude y longitude van juntos\" intentos=0"));
    }

    @Test
    void recuperableAgotadoVaALaDltTrasCuatroEjecuciones(CapturedOutput output) throws Exception {
        String requestId = newRequestId();
        doThrow(new QueryTimeoutException("Oracle no disponible"))
                .when(kpiService).apply(argThat((RequestEvent e) -> e != null && requestId.equals(e.requestId())));
        ObjectNode event = rated(requestId, 7, "2026-11-10T13:00:00Z", 4);
        RecordMetadata sent = send(broker, TOPIC, requestId, event.toString());

        ConsumerRecord<String, String> dead = findByKey(broker, DLT, requestId, WAIT).orElseThrow();

        assertThat(header(dead, "kafka_dlt-exception-message")).contains("Oracle no disponible");
        verify(kpiService, times(RequestEventsKafkaConfig.MAX_ATTEMPTS))
                .apply(argThat(e -> e != null && requestId.equals(e.requestId())));
        await().atMost(WAIT).until(() ->
                committedOffset(broker, RequestEventsListener.GROUP, TOPIC, sent.partition()) > sent.offset());
        await().atMost(WAIT).until(() -> output.getOut().contains("eventId=" + event.get("eventId").asText()
                + " motivo=\"Oracle no disponible\" intentos=4"));
        assertThat(projections.findById(requestId)).isEmpty();
    }

    @Test
    void desconocidoSeConfirmaSinDlt() throws Exception {
        String requestId = newRequestId();
        ObjectNode event = rated(requestId, 1, "2026-11-10T13:00:00Z", 5);
        event.put("eventType", "request.reopened");
        RecordMetadata sent = send(broker, TOPIC, requestId, event.toString());

        await().atMost(WAIT).until(() ->
                committedOffset(broker, RequestEventsListener.GROUP, TOPIC, sent.partition()) > sent.offset());
        assertThat(findByKey(broker, DLT, requestId, Duration.ofSeconds(3))).isEmpty();
        verify(kpiService, never()).apply(argThat(e -> e != null && requestId.equals(e.requestId())));
    }
}
