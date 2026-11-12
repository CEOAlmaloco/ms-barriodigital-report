package cl.duoc.barriodigital.report.messaging;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Envío, lectura de la DLT y offsets confirmados contra el broker embebido. */
final class KafkaTestSupport {

    private KafkaTestSupport() {
    }

    static RecordMetadata send(EmbeddedKafkaBroker broker, String topic, String key, String value) throws Exception {
        Map<String, Object> props = KafkaTestUtils.producerProps(broker);
        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
            return producer.send(new ProducerRecord<>(topic, key, value)).get();
        }
    }

    /** Offset confirmado por el grupo en esa partición, o -1 si no confirmó nada. */
    static long committedOffset(EmbeddedKafkaBroker broker, String group, String topic, int partition)
            throws Exception {
        try (AdminClient admin = admin(broker)) {
            OffsetAndMetadata offset = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata().get()
                    .get(new TopicPartition(topic, partition));
            return offset == null ? -1 : offset.offset();
        }
    }

    static Optional<ConsumerRecord<String, String>> findByKey(EmbeddedKafkaBroker broker, String topic, String key,
                                                              Duration timeout) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("verificacion-" + UUID.randomUUID(), "false", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                    if (key.equals(record.key())) {
                        return Optional.of(record);
                    }
                }
            }
        }
        return Optional.empty();
    }

    static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    static AdminClient admin(EmbeddedKafkaBroker broker) {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()));
    }
}
