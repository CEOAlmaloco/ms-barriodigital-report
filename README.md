# ms-barriodigital-report

Microservicio de reportería de **BarrioDigital**: KPIs y plazos de los trámites.

Puerto: **8086**

## Responsabilidad

Consume `requests.events` y mantiene una proyección por trámite en Oracle, que es la base de los KPIs y los plazos. Las consultas leen esa proyección y nunca reprocesan eventos.

Los contratos están en `barriodigital-infra/docs/decisiones.md`: tópicos y eventos Kafka, publicación y consumo, plazos en días hábiles y APIs de report y audit.

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/report/kpis?range=last24h\|last7d` | Altas por tramo, abiertos por estado, promedio de resolución y cumplimiento de plazo. Admin y Auditor |
| GET | `/api/report/top-procedures?range=last24h\|last7d&limit=5` | Tipos con más altas en la ventana. Admin y Auditor |
| GET | `/api/report/sla?status=VENCIDO,POR_VENCER&assignee=me\|none&page=0&size=20` | Trámites abiertos vencidos o por vencer, por fecha de vencimiento. Funcionario, Admin y Auditor |
| GET | `/api/report/sla/batch?requestIds=<uuid>,...` | Estado y fecha de vencimiento de hasta 50 trámites, en el orden pedido. Funcionario, Admin y Auditor |
| GET | `/actuator/health` | Estado del servicio, incluida la base de datos (`db`) |
| GET | `/actuator/health/sla` | Calendario de feriados y preparación de plazos, con detalle visible |
| GET | `/actuator/info` | Información del servicio |

Solo el BFF llama a `/api/report/**`, sin JWT: el servicio vuelve a comprobar `X-User-Roles`, que debe traer exactamente un rol permitido. Si no, responde 403. Los parámetros se validan después del rol (400). Los errores llevan `status`, `error`, `message` y `path`.

```powershell
Invoke-RestMethod "http://localhost:8086/api/report/kpis?range=last7d" -Headers @{ "X-User-Roles" = "Admin" }
```

`show-details` es `when_authorized`: sin autorización, el health devuelve solo el estado general, sin el detalle por componente. El grupo `sla` es la excepción: muestra su detalle siempre, porque es informativo y no expone la base. El health no incluye Kafka.

## Stack

- Java 21 y Spring Boot 3.3.5
- `spring-kafka`, `spring-boot-starter-data-jpa`, `spring-boot-starter-web`, `spring-boot-starter-validation` y `spring-boot-starter-actuator`
- Oracle Autonomous con `ojdbc11` y wallet (`oraclepki`, `osdt_core`, `osdt_cert`)
- H2 y `spring-kafka-test` (Kafka embebido) solo en tests

## Variables

Copiar `.env.example` a `.env` y completar. `.env` y el wallet nunca se suben a git.

| Variable | Uso |
|---|---|
| `ORACLE_URL` | URL JDBC con el alias del `tnsnames.ora`, por ejemplo `jdbc:oracle:thin:@barriodig_low?TNS_ADMIN=...` |
| `ORACLE_USER`, `ORACLE_PASSWORD` | Usuario del esquema, el mismo que usa requests |
| `ORACLE_WALLET_DIR` | Carpeta del wallet descomprimido, fuera del repo |
| `KAFKA_BOOTSTRAP_SERVERS` | Brokers de Kafka. Por defecto `localhost:9092` |

## Oracle

Antes de crear el pool, `OracleWalletConfiguration` comprueba que `ORACLE_WALLET_DIR` exista y tenga `tnsnames.ora`, y la fija como `oracle.net.tns_admin`. Si falta la carpeta o el archivo, el servicio no arranca e indica cuál falta. Sin la variable, usa la conexión tal como esté configurada.

Al arrancar, `OracleDataSourceProbe` abre una conexión y registra `Oracle OK: pool conectado (...)` con el producto y la versión, sin URL ni clave. Si Oracle no responde, el servicio no arranca.

El pool Hikari (`barriodigital-report-oracle`) admite hasta 5 conexiones. Las fechas se guardan en UTC.

El servicio comparte el esquema de Oracle con los demás servicios de BarrioDigital.

### Tablas

| Tabla | Contenido |
|---|---|
| `report_request_projection` | Una fila por trámite: tipo, sector, coordenadas, estado, responsable, alta, cierre, plazo y `last_event_at`. Sin `created_at` es una proyección parcial y no cuenta en los KPIs |
| `processed_events` | Un `(consumer, event_id)` por evento aplicado, con la clave `pk_processed_events`. Reconoce los duplicados |

**Antes del primer arranque**, aplicar `src/main/resources/db/report-tables.sql` en Oracle (SQLcl, SQL Developer o el worksheet de Autonomous). Se puede correr más de una vez. Si Hibernate creó `processed_events` antes, el script renombra la clave sin tocar las filas. `ProcessedEventsSchemaCheck` detiene el arranque si la clave no se llama `pk_processed_events`.

`db/schema.sql` es la referencia de las dos tablas y sus índices.

## Kafka

| | Valor |
|---|---|
| Tópico | `requests.events` |
| Grupo | `report-group`, un listener con concurrencia 3 (un hilo por partición) |
| DLT | `requests.events.report.DLT`, en la misma partición que el mensaje original |
| Confirmación | `enable-auto-commit: false` y `ack-mode: record`: el offset se confirma después del commit en Oracle |

`spring.kafka.admin.auto-create` es `false`: el servicio no crea tópicos, porque eso lo hace solo kafka-admin. Arranca aunque Kafka no esté disponible: el listener se conecta cuando el broker responde. El listener no arranca solo: lo inicia `SlaStartup` al terminar la preparación de plazos (sección Plazos), y solo si `barriodigital.kafka.listener.auto-startup` es `true`.

### Qué pasa con cada mensaje

| Caso | Acción | Log |
|---|---|---|
| Evento válido | Marca `processed_events` y actualiza la proyección en una transacción, después confirma | |
| `eventId` ya procesado | Confirma sin reprocesar | `INFO duplicado` |
| `eventType` desconocido | Confirma sin procesar | `WARN evento-desconocido` |
| Malformado o `schemaVersion` no soportada | A la DLT sin reintentos | `ERROR evento-muerto ... intentos=0` |
| Error recuperable (Oracle caído) | Reintenta a 1, 2 y 4 s sin soltar la partición, después a la DLT | `ERROR evento-muerto ... intentos=4` |
| La DLT no confirma | El mensaje no se da por recuperado: el offset no avanza y se vuelve a leer hasta que la DLT responde | `ERROR dlt-no-disponible` |

### Orden y eventos atrasados

El estado y el responsable guardan cada uno el `sequence` del último evento que los cambió (`status_sequence` y `assignee_sequence`). Un evento atrasado no cambia su aspecto, pero completa el alta y el cierre si faltan. Las reglas completas están en el contrato de publicación y consumo, sección 9.3.

Un evento de un trámite sin proyección crea una proyección parcial, sin `created_at` y con `sla_status` nulo, que el `request.created` posterior completa. `RESUELTO`, `RECHAZADO` y `CANCELADO` escriben `closed_at`.

### Dos instancias

Dos instancias se reparten las 3 particiones de `report-group`, 2 y 1. Los hilos se llaman `report-0`, `report-1` y `report-2` en las dos, y `RangeAssignor` ordena los miembros por id, así que los de ambas instancias se intercalan. Para verlo en Kafka UI, con el clúster de `barriodigital-infra/kafka` levantado:

```powershell
.\mvnw.cmd spring-boot:run
# En otra terminal:
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8096"
```

En Kafka UI (`http://localhost:8090`), Consumers, `report-group`: las particiones 0, 1 y 2 quedan asignadas a miembros de los dos hosts o procesos. Al detener una instancia, la otra vuelve a tomar las 3.

## Plazos

Cada alta con plazo calcula `due_date` y `due_soon_date` en días hábiles de Chile, y `sla_status` sigue el ciclo del trámite: `DENTRO_DE_PLAZO`, `POR_VENCER` o `VENCIDO` mientras está abierto, y `CUMPLIDO`, `FUERA_DE_PLAZO` o `NO_APLICA` (cancelado) al cerrarse. Un tipo sin plazo queda en `SIN_PLAZO`. Las reglas están en el contrato de plazos en días hábiles.

### Feriados

`src/main/resources/holidays-cl.yml` tiene los feriados nacionales de 2026 y 2027, con su fuente. Un archivo mal formado impide arrancar. Un año que falta no: se cuenta solo con fines de semana, se registra `WARN calendario-feriados-incompleto year=...` y aparece en `missingYears` de `/actuator/health/sla`.

**Para agregar un año:** sumar sus fechas desde la fuente oficial (gob.cl, BCN o Diario Oficial), citar la fuente en el archivo y redesplegar. Al arrancar, report recalcula los plazos abiertos con el calendario nuevo.

### Arranque

Antes de iniciar el listener, `SlaStartup` corre en orden:

1. **Pendientes:** evalúa las altas con `sla_status` nulo, proyectadas antes de que report calculara plazos.
2. **Recálculo:** vuelve a calcular las fechas de los trámites abiertos con plazo, uno por transacción.
3. **Estado:** el `UPDATE` de `sla_status` sobre los trámites abiertos.

Un paso que falla no detiene el listener: queda `ERROR plazos-preparacion-incompleta` en el log y el resultado en `/actuator/health/sla`. El siguiente arranque lo corrige.

## Tareas programadas

| Tarea | Cuándo | Qué hace |
|---|---|---|
| `SlaMaintenance` | 00:05 en `America/Santiago` | Actualiza `sla_status` de los trámites abiertos. Ante un bloqueo de Oracle reintenta una vez, en una transacción nueva |
| `ProcessedEventsCleanup` | 03:30 en `America/Santiago` | Borra las filas de `processed_events` del consumidor `report` con más de 30 días |

El domingo del inicio del horario de verano no existen las 00:05 y el job no corre ese día. No cambia nada, porque ningún estado de plazo cambia en domingo.

## Levantar localmente

Con `.env` completo en la raíz del repo:

```powershell
.\mvnw.cmd spring-boot:run
Invoke-RestMethod http://localhost:8086/actuator/health
```

Debe responder `status: UP` y el log debe mostrar `Oracle OK`.

## Docker

```powershell
docker build -t ms-barriodigital-report .
```

Build multi-stage con Maven y Java 21. La imagen no incluye el wallet ni el `.env`: se montan o se pasan como variables al ejecutar. Incluye `curl` para el healthcheck.

## Compilar y probar

```powershell
.\mvnw.cmd verify
```

Los tests usan H2 en modo Oracle, con las tablas de `src/test/resources/db/h2-report-tables.sql`, y Kafka embebido. No necesitan Oracle ni Kafka reales:

- `ReportApplicationTest`: el contexto arranca con el puerto 8086, el nombre del servicio y sin creación automática de tópicos.
- `OracleWalletConfigurationTest`: sin variable no hace nada; una carpeta inexistente o sin `tnsnames.ora` impide arrancar; un wallet válido fija `oracle.net.tns_admin`.
- `ProcessedEventsSchemaCheckTest`: no arranca sin tabla o con una clave primaria de otro nombre.
- `RequestProjectionTest`: secuencias por aspecto, `request.created` atrasado con la proyección abierta o cerrada, cancelación y `last_event_at`.
- `RequestEventMapperTest`: los seis tipos, campos desconocidos, sobre y payload malformados, tipos no estrictos, `schemaVersion` mayor y `eventType` desconocido.
- `RequestEventsListenerH2Test`: duplicado, malformado, desconocido, atrasado, parcial completada abierta y cerrada, y la marca y la proyección en la misma transacción.
- `DuplicateEventsTest`: solo `pk_processed_events` es un duplicado.
- `ProcessedEventsCleanupH2Test`: borra solo lo de report con más de 30 días.
- `RequestEventsKafkaTest`: offset confirmado, malformado a la DLT con sus headers, recuperable agotado tras 4 ejecuciones y desconocido sin DLT.
- `RequestEventsDltUnavailableKafkaTest`: sin DLT el offset no avanza; al crearla, el mensaje llega y se confirma.
- `RequestEventsBalancingKafkaTest`: dos instancias se reparten las 3 particiones.
- `ReportRangeTest`: tramos por hora UTC y por día de Chile, con días de 23 y 25 horas por cambio de horario, y fin de la ventana en el instante actual.
- `KpiQueryServiceH2Test`: ceros y `null` sin filas o sin filas que calcen, parciales fuera de los KPIs, límites de tramo, altas y cierres posteriores al reloj fuera de todas las consultas, promedio solo de resueltos y sin depender del offset guardado, cumplimiento sin `SIN_PLAZO`, orden y límite de `top-procedures` con desempate por code point, label tras renombrar y desempate con altas en el mismo instante, incluso con tildes, mayúsculas y emojis.
- `KpiControllerTest`: roles permitidos y 403 (Vecino, Funcionario, sin rol, dos roles), 403 antes que 400, mensajes de `range` y `limit`, valores por defecto, `null` serializados y cuerpo de error.
- `HolidaysPropertiesTest`: 16 feriados en 2026 y 17 en 2027, sin el 31 de diciembre ni regionales, traslados en lunes, y rechazo de fechas repetidas o bajo otro año.
- `SlaCalculatorTest`: alta en día hábil, en sábado, en feriado y de noche en Chile, feriados en el conteo, cambio de año, año sin calendario, umbral de por vencer, plazo de 1 día y cierre por fecha en Chile.
- `RequestProjectionSlaTest`: alta con y sin plazo, cumplido, fuera de plazo, cancelado, resultado evaluado que no cambia, cierre con fecha recalculada y parciales completadas.
- `SlaMaintenanceH2Test`: pendientes evaluadas, recálculo de un feriado, vencido que vuelve a dentro de plazo, los tres estados, sin plazo, parciales y cerrados intactos, cierre con la fila bloqueada durante el recálculo y dos instancias a la vez.
- `SlaMaintenanceTest`: reintento único ante bloqueo en una transacción nueva, sin reintento ante otro error, un trámite que falla no detiene al resto, y el cron en los cambios de horario.
- `SlaStartupTest`: orden pendientes, recálculo, estado y listener, y listener iniciado aunque la preparación falle.
- `SlaControllerTest`: roles y 403, orden por vencimiento y folio, paginación, `assignee=me` con y sin `X-User-Id`, `none`, mensajes de error, y lote en orden con desconocidos, parciales, sin plazo, mayúsculas y límites.
- `SlaHealthTest`: el grupo `sla` muestra su detalle y el health general no.

## Git

Ramas de tarea desde `develop` y PR hacia `develop`, según `barriodigita-info/convenciones.md`.
