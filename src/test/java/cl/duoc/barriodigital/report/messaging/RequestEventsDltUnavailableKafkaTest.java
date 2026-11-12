package cl.duoc.barriodigital.report.messaging;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.List;

import static cl.duoc.barriodigital.report.messaging.KafkaTestSupport.admin;
import static cl.duoc.barriodigital.report.messaging.KafkaTestSupport.committedOffset;
import static cl.duoc.barriodigital.report.messaging.KafkaTestSupport.findByKey;
import static cl.duoc.barriodigital.report.messaging.KafkaTestSupport.send;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.DLT;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.TOPIC;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.created;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.newRequestId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

/** El broker no crea tópicos solo: la DLT no existe hasta que el test la crea. */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "barriodigital.kafka.listener.auto-startup=true",
        "spring.kafka.producer.properties.max.block.ms=1000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.delivery.timeout.ms=2000"})
@EmbeddedKafka(partitions = 3, topics = TOPIC, brokerProperties = "auto.create.topics.enable=false")
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class RequestEventsDltUnavailableKafkaTest {

    private static final Duration WAIT = Duration.ofSeconds(45);

    @Autowired
    private EmbeddedKafkaBroker broker;

    @SpyBean
    private RequestEventMapper mapper;

    @Test
    void sinDltElOffsetNoAvanzaYSeReintentaHastaQueLaDltResponda(CapturedOutput output) throws Exception {
        String requestId = newRequestId();
        ObjectNode event = created(requestId, 1, "2026-11-10T13:00:00Z", 5);
        event.put("schemaVersion", 2);
        String value = event.toString();
        RecordMetadata sent = send(broker, TOPIC, requestId, value);

        await().atMost(WAIT).untilAsserted(() -> verify(mapper, atLeast(3)).map(value));
        assertThat(committedOffset(broker, RequestEventsListener.GROUP, TOPIC, sent.partition()))
                .isLessThanOrEqualTo(sent.offset());
        assertThat(output.getOut()).contains("dlt-no-disponible topic=requests.events partition=" + sent.partition()
                + " offset=" + sent.offset() + " eventId=" + event.get("eventId").asText());
        assertThat(output.getOut()).doesNotContain("evento-muerto topic=requests.events partition="
                + sent.partition() + " offset=" + sent.offset());

        try (AdminClient admin = admin(broker)) {
            admin.createTopics(List.of(new NewTopic(DLT, 3, (short) 1))).all().get();
        }

        ConsumerRecord<String, String> dead = findByKey(broker, DLT, requestId, WAIT).orElseThrow();
        assertThat(dead.value()).isEqualTo(value);
        await().atMost(WAIT).until(() ->
                committedOffset(broker, RequestEventsListener.GROUP, TOPIC, sent.partition()) > sent.offset());
        await().atMost(WAIT).until(() -> output.getOut().contains("evento-muerto topic=requests.events partition="
                + sent.partition() + " offset=" + sent.offset()));
    }
}
