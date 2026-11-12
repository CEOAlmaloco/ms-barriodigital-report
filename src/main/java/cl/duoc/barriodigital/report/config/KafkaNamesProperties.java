package cl.duoc.barriodigital.report.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Tópicos de Kafka de report. Si falta uno, el servicio no arranca. */
@Validated
@ConfigurationProperties(prefix = "barriodigital.kafka")
public record KafkaNamesProperties(@Valid @NotNull Topic topic) {

    public record Topic(@NotBlank String requestsEvents, @NotBlank String requestsEventsDlt) {
    }
}
