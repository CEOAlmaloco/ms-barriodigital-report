package cl.duoc.barriodigital.report.domain;

/** Estado del plazo. null en la proyección significa "sin dato", distinto de SIN_PLAZO. */
public enum SlaStatus {
    DENTRO_DE_PLAZO,
    POR_VENCER,
    VENCIDO,
    CUMPLIDO,
    FUERA_DE_PLAZO,
    SIN_PLAZO
}
