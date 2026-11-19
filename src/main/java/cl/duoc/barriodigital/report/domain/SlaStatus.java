package cl.duoc.barriodigital.report.domain;

/** Estado del plazo. null en la proyección significa "sin dato", distinto de SIN_PLAZO. */
public enum SlaStatus {
    DENTRO_DE_PLAZO,
    POR_VENCER,
    VENCIDO,
    CUMPLIDO,
    FUERA_DE_PLAZO,
    /** Cancelado con plazo: nadie lo resolvió ni lo rechazó, así que no entra en el cumplimiento. */
    NO_APLICA,
    SIN_PLAZO;

    /** Estados ya evaluados al cierre: ni el listener ni los procesos derivados los vuelven a calcular. */
    public boolean isFinal() {
        return this == CUMPLIDO || this == FUERA_DE_PLAZO || this == NO_APLICA;
    }
}
