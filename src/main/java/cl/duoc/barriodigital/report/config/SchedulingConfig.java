package cl.duoc.barriodigital.report.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Programa las tareas de report. Los tests lo apagan. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "barriodigital.report.scheduling", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class SchedulingConfig {
}
