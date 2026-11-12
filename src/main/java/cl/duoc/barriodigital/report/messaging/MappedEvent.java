package cl.duoc.barriodigital.report.messaging;

import cl.duoc.barriodigital.report.domain.RequestEvent;

/** Resultado de leer un mensaje: un evento válido o un eventType que report no maneja. */
sealed interface MappedEvent {

    record Known(RequestEvent event) implements MappedEvent {
    }

    record Unknown(String eventType, String eventId) implements MappedEvent {
    }
}
