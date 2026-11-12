package cl.duoc.barriodigital.report.persistence;

import cl.duoc.barriodigital.report.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Key> {

    @Transactional
    @Modifying
    @Query("delete from ProcessedEvent e where e.consumer = :consumer and e.processedAt < :limit")
    int deleteByConsumerProcessedBefore(@Param("consumer") String consumer, @Param("limit") Instant limit);
}
