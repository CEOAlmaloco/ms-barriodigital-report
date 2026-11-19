-- =====================================================================================
-- Tablas de report. Oracle Autonomous. Se aplica ANTES del primer arranque de report. Se puede correr más de una vez.
--
-- El duplicado se reconoce solo por el nombre pk_processed_events, y ddl-auto: update no sabe nombrar una clave
-- primaria. Si Hibernate creó la tabla primero, el paso 1 renombra la clave sin tocar las filas.
-- =====================================================================================

WHENEVER SQLERROR EXIT ROLLBACK

-- Vista previa, solo lectura: tablas existentes y nombre de su clave primaria.
SELECT t.table_name,
       (SELECT c.constraint_name FROM user_constraints c
        WHERE c.table_name = t.table_name AND c.constraint_type = 'P') AS clave_primaria
FROM user_tables t
WHERE t.table_name IN ('PROCESSED_EVENTS', 'REPORT_REQUEST_PROJECTION');

-- 1. processed_events: se crea, o se renombra su clave primaria si Hibernate la creó con otro nombre.
DECLARE
    v_tables NUMBER;
    v_pk     VARCHAR2(128);
BEGIN
    SELECT COUNT(*) INTO v_tables FROM user_tables WHERE table_name = 'PROCESSED_EVENTS';
    IF v_tables = 0 THEN
        EXECUTE IMMEDIATE q'[
            CREATE TABLE processed_events (
                consumer     VARCHAR2(40) NOT NULL,
                event_id     VARCHAR2(36) NOT NULL,
                processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                CONSTRAINT pk_processed_events PRIMARY KEY (consumer, event_id)
            )]';
    ELSE
        SELECT MAX(constraint_name) INTO v_pk FROM user_constraints
        WHERE table_name = 'PROCESSED_EVENTS' AND constraint_type = 'P';
        IF v_pk IS NULL THEN
            RAISE_APPLICATION_ERROR(-20036, 'processed_events existe sin clave primaria: revisar a mano');
        ELSIF v_pk <> 'PK_PROCESSED_EVENTS' THEN
            EXECUTE IMMEDIATE 'ALTER TABLE processed_events RENAME CONSTRAINT "' || v_pk
                || '" TO pk_processed_events';
        END IF;
    END IF;
END;
/

-- 2. Proyección por trámite.
DECLARE
    v_tables NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_tables FROM user_tables WHERE table_name = 'REPORT_REQUEST_PROJECTION';
    IF v_tables = 0 THEN
        EXECUTE IMMEDIATE q'[
            CREATE TABLE report_request_projection (
                request_id        VARCHAR2(36)      NOT NULL,
                folio             VARCHAR2(14 CHAR) NOT NULL,
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
                last_sequence     NUMBER(19)        NOT NULL,
                created_at        TIMESTAMP WITH TIME ZONE,
                sla_business_days NUMBER(10),
                due_date          DATE,
                due_soon_date     DATE,
                sla_status        VARCHAR2(20),
                closed_at         TIMESTAMP WITH TIME ZONE,
                last_event_at     TIMESTAMP WITH TIME ZONE NOT NULL,
                CONSTRAINT pk_report_request_projection PRIMARY KEY (request_id)
            )]';
    END IF;
END;
/

-- 3. Índices de las consultas de KPIs y plazos. Solo se crean los que faltan.
DECLARE
    PROCEDURE create_index(p_name VARCHAR2, p_column VARCHAR2) IS
        v_count NUMBER;
    BEGIN
        SELECT COUNT(*) INTO v_count FROM user_indexes WHERE index_name = UPPER(p_name);
        IF v_count = 0 THEN
            EXECUTE IMMEDIATE 'CREATE INDEX ' || p_name || ' ON report_request_projection (' || p_column || ')';
        END IF;
    END;
BEGIN
    create_index('idx_report_projection_created', 'created_at');
    create_index('idx_report_projection_closed', 'closed_at');
    create_index('idx_report_projection_status', 'status');
    create_index('idx_report_projection_sla_status', 'sla_status');
    create_index('idx_report_projection_due_date', 'due_date');
    create_index('idx_report_projection_assignee', 'assignee_id');
    create_index('idx_report_projection_last_event', 'last_event_at');
END;
/

-- Resultado: las dos tablas con su clave primaria con nombre.
SELECT table_name, constraint_name FROM user_constraints
WHERE table_name IN ('PROCESSED_EVENTS', 'REPORT_REQUEST_PROJECTION') AND constraint_type = 'P';
