package cl.duoc.barriodigital.report.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Marca de idempotencia. isNew es siempre true para que save haga persist y un eventId repetido viole
 * pk_processed_events, en vez de un merge que actualice la fila. Por lo mismo, delete(entidad) no borra:
 * se borra con consultas.
 */
@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEvent.Key.class)
public class ProcessedEvent implements Persistable<ProcessedEvent.Key> {

    public static final String REPORT_CONSUMER = "report";

    @Id
    @Column(name = "consumer", length = 40, nullable = false)
    private String consumer;

    @Id
    @Column(name = "event_id", length = 36, nullable = false)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(String consumer, String eventId, Instant processedAt) {
        this.consumer = consumer;
        this.eventId = eventId;
        this.processedAt = processedAt;
    }

    @Override
    public Key getId() {
        return new Key(consumer, eventId);
    }

    @Override
    public boolean isNew() {
        return true;
    }

    public String getConsumer() {
        return consumer;
    }

    public String getEventId() {
        return eventId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public static class Key implements Serializable {

        private String consumer;
        private String eventId;

        protected Key() {
        }

        public Key(String consumer, String eventId) {
            this.consumer = consumer;
            this.eventId = eventId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(consumer, key.consumer)
                    && Objects.equals(eventId, key.eventId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumer, eventId);
        }
    }
}
