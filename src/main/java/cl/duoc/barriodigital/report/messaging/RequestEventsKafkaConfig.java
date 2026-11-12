package cl.duoc.barriodigital.report.messaging;

import cl.duoc.barriodigital.report.config.KafkaNamesProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;

/**
 * Errores del consumidor de requests.events: reintentos bloqueantes a 1, 2 y 4 s, para no desordenar un trámite,
 * y después la DLT en la misma partición. Malformados e incompatibles van directo, sin reintentos.
 *
 * <p>Si la DLT no confirma, el recoverer relanza la excepción: el offset no se confirma y la partición se detiene
 * hasta que la DLT responda.
 */
@Configuration
@EnableConfigurationProperties(KafkaNamesProperties.class)
public class RequestEventsKafkaConfig {

    static final int MAX_RETRIES = 3;
    static final int MAX_ATTEMPTS = MAX_RETRIES + 1;
    private static final int MAX_REASON_LENGTH = 300;

    private static final Logger log = LoggerFactory.getLogger(RequestEventsKafkaConfig.class);

    @Bean
    DefaultErrorHandler requestEventsErrorHandler(KafkaOperations<?, ?> kafka, KafkaNamesProperties names,
                                                  RequestEventMapper mapper) {
        String dlt = names.topic().requestsEventsDlt();
        DeadLetterPublishingRecoverer dltPublisher = new DeadLetterPublishingRecoverer(kafka,
                (record, ex) -> new TopicPartition(dlt, record.partition()));
        dltPublisher.setFailIfSendResultIsError(true);

        ConsumerRecordRecoverer recoverer = (record, ex) -> {
            String eventId = eventId(record, mapper);
            try {
                dltPublisher.accept(record, ex);
            } catch (RuntimeException dltFailure) {
                log.error("dlt-no-disponible topic={} partition={} offset={} eventId={} dlt={} motivo=\"{}\"",
                        record.topic(), record.partition(), record.offset(), eventId, dlt,
                        clean(String.valueOf(dltFailure.getMessage())));
                throw dltFailure;
            }
            log.error("evento-muerto topic={} partition={} offset={} eventId={} motivo=\"{}\" intentos={}",
                    record.topic(), record.partition(), record.offset(), eventId, reason(ex), attempts(ex));
        };

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(4_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(NonRetryableEventException.class);
        return handler;
    }

    /** Veces que se ejecutó el procesamiento: 0 en un rechazo directo. */
    static int attempts(Throwable ex) {
        return find(ex, NonRetryableEventException.class) != null || find(ex, DeserializationException.class) != null
                ? 0 : MAX_ATTEMPTS;
    }

    static String reason(Throwable ex) {
        Throwable source = find(ex, NonRetryableEventException.class);
        if (source == null) {
            source = ex;
            while (source.getCause() != null && source.getCause() != source) {
                source = source.getCause();
            }
        }
        return clean(source.getMessage() != null ? source.getMessage() : source.getClass().getSimpleName());
    }

    /** Una línea, sin comillas dobles y acotada, para que el log se pueda filtrar. */
    private static String clean(String message) {
        String clean = message.replace('"', '\'').replaceAll("\\s+", " ").trim();
        return clean.length() > MAX_REASON_LENGTH ? clean.substring(0, MAX_REASON_LENGTH) : clean;
    }

    private static String eventId(ConsumerRecord<?, ?> record, RequestEventMapper mapper) {
        String eventId = record.value() instanceof String json ? mapper.peekEventId(json) : null;
        return eventId == null ? "desconocido" : eventId;
    }

    private static <T extends Throwable> T find(Throwable ex, Class<T> type) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }
}
