package cl.duoc.barriodigital.report.messaging;

/** Evento malformado o incompatible: va a la DLT sin reintentos. */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
