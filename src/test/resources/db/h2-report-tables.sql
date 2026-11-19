-- Solo tests. Igual que en Oracle, las tablas las crea un script con las restricciones con nombre.
-- Misma forma que src/main/resources/db/schema.sql. Corre después de que Hibernate arma el esquema de H2.
DROP TABLE IF EXISTS processed_events;
DROP TABLE IF EXISTS report_request_projection;

-- En Oracle el índice de la clave lleva el nombre de la restricción, y ORA-00001 informa ese nombre.
-- H2 informa el índice, que llamaría PRIMARY_KEY_n: el índice con nombre propio hace que informe el mismo.
CREATE TABLE processed_events (
    consumer     VARCHAR2(40) NOT NULL,
    event_id     VARCHAR2(36) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX pk_processed_events ON processed_events (consumer, event_id);
ALTER TABLE processed_events ADD CONSTRAINT pk_processed_events PRIMARY KEY (consumer, event_id);

CREATE TABLE report_request_projection (
    request_id        VARCHAR2(36)  NOT NULL,
    folio             VARCHAR2(14)  NOT NULL,
    procedure_type    VARCHAR2(100),
    procedure_label   VARCHAR2(120),
    sector            VARCHAR2(40),
    latitude          NUMBER,
    longitude         NUMBER,
    status            VARCHAR2(30),
    status_sequence   NUMBER(19),
    assignee_id       VARCHAR2(64),
    assignee_name     VARCHAR2(200),
    assignee_sequence NUMBER(19),
    last_sequence     NUMBER(19)    NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE,
    sla_business_days NUMBER(10),
    due_date          DATE,
    due_soon_date     DATE,
    sla_status        VARCHAR2(20),
    closed_at         TIMESTAMP WITH TIME ZONE,
    last_event_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_report_request_projection PRIMARY KEY (request_id)
);

CREATE INDEX idx_report_projection_created ON report_request_projection (created_at);
CREATE INDEX idx_report_projection_closed ON report_request_projection (closed_at);
CREATE INDEX idx_report_projection_status ON report_request_projection (status);
CREATE INDEX idx_report_projection_sla_status ON report_request_projection (sla_status);
CREATE INDEX idx_report_projection_due_date ON report_request_projection (due_date);
CREATE INDEX idx_report_projection_assignee ON report_request_projection (assignee_id);
CREATE INDEX idx_report_projection_last_event ON report_request_projection (last_event_at);
