-- =====================================================================================
-- Reparación de assignee_name guardado como oid en la proyección. Oracle Autonomous.
--
-- report copia el nombre de cada request.assigned. Antes del fix de requests, algunos eventos traían el oid como
-- nombre y la proyección lo conserva, porque los eventos viejos no se reemiten. Este script corrige solo las filas
-- cuyo nombre es igual al id, con el nombre de staff_member (mismo esquema que requests) cuando ese nombre es
-- válido: no vacío y distinto del oid. Sin fuente válida, la fila no se toca.
--
-- No reprocesa ni modifica eventos, processed_events ni assignee_sequence: el orden de la proyección no cambia y
-- un request.assigned nuevo sigue aplicándose igual. Se puede correr más de una vez.
-- =====================================================================================

WHENEVER SQLERROR EXIT ROLLBACK

-- Vista previa, solo lectura: qué se va a reparar y con qué nombre.
SELECT p.request_id, p.folio, p.assignee_id AS oid, TRIM(s.name) AS nombre
FROM report_request_projection p
JOIN staff_member s ON s.oid = p.assignee_id
WHERE p.assignee_name = p.assignee_id
  AND TRIM(s.name) IS NOT NULL AND TRIM(s.name) <> s.oid;

UPDATE report_request_projection p
SET p.assignee_name = (SELECT TRIM(s.name) FROM staff_member s WHERE s.oid = p.assignee_id)
WHERE p.assignee_name = p.assignee_id
  AND EXISTS (SELECT 1 FROM staff_member s
              WHERE s.oid = p.assignee_id AND TRIM(s.name) IS NOT NULL AND TRIM(s.name) <> s.oid);

COMMIT;

-- Comprobación: filas que siguen con el oid como nombre (sin fuente válida todavía).
SELECT COUNT(*) AS pendientes FROM report_request_projection WHERE assignee_name = assignee_id;
