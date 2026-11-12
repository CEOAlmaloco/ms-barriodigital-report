package cl.duoc.barriodigital.report.messaging;

import cl.duoc.barriodigital.report.ReportApplication;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.condition.EmbeddedKafkaCondition;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.DLT;
import static cl.duoc.barriodigital.report.messaging.RequestEventsFixtures.TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Cada contexto es una instancia de report, con su propio listener de concurrencia 3. */
@EmbeddedKafka(partitions = 3, topics = {TOPIC, DLT})
class RequestEventsBalancingKafkaTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Test
    void dosInstanciasSeRepartenLasTresParticiones() {
        EmbeddedKafkaBroker broker = EmbeddedKafkaCondition.getBroker();
        try (ConfigurableApplicationContext first = start(broker)) {
            await().atMost(WAIT).until(() -> partitions(first).size() == 3);

            try (ConfigurableApplicationContext second = start(broker)) {
                await().atMost(WAIT).until(() -> {
                    Set<Integer> a = partitions(first);
                    Set<Integer> b = partitions(second);
                    return !a.isEmpty() && !b.isEmpty() && a.size() + b.size() == 3;
                });

                Set<Integer> all = new HashSet<>(partitions(first));
                all.addAll(partitions(second));
                assertThat(all).containsExactlyInAnyOrder(0, 1, 2);
            }

            await().atMost(WAIT).until(() -> partitions(first).size() == 3);
        }
    }

    private static ConfigurableApplicationContext start(EmbeddedKafkaBroker broker) {
        // Como argumentos, para que ganen sobre el application.properties de test.
        return new SpringApplicationBuilder(ReportApplication.class).run(
                "--server.port=0",
                "--spring.kafka.bootstrap-servers=" + broker.getBrokersAsString(),
                "--barriodigital.kafka.listener.auto-startup=true");
    }

    private static Set<Integer> partitions(ConfigurableApplicationContext context) {
        MessageListenerContainer container = context.getBean(KafkaListenerEndpointRegistry.class)
                .getListenerContainer(RequestEventsListener.LISTENER_ID);
        Collection<TopicPartition> assigned = container == null ? null : container.getAssignedPartitions();
        return assigned == null ? Set.of()
                : assigned.stream().map(TopicPartition::partition).collect(Collectors.toSet());
    }
}
