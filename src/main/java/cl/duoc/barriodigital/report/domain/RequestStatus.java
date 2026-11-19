package cl.duoc.barriodigital.report.domain;

/** Mismos nombres que en requests. */
public enum RequestStatus {
    INGRESADO,
    ADMITIDO,
    EN_GESTION,
    EN_TERRENO,
    RESUELTO,
    RECHAZADO,
    CANCELADO;

    /** Estados terminales: escriben closedAt y detienen el plazo. */
    public boolean isClosed() {
        return this == RESUELTO || this == RECHAZADO || this == CANCELADO;
    }
}
