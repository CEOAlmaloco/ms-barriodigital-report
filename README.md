# ms-barriodigital-report

Microservicio de reportería de **BarrioDigital**: KPIs y plazos de los trámites.

Puerto: **8086**

## Responsabilidad

Consume `requests.events` y mantiene una proyección por trámite en Oracle, que es la base de los KPIs y los plazos. Las consultas leen esa proyección y nunca reprocesan eventos.

Los contratos están en `barriodigital-infra/docs/decisiones.md`: tópicos y eventos Kafka, publicación y consumo, plazos en días hábiles y APIs de report y audit.

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/actuator/health` | Estado del servicio, incluida la base de datos (`db`) |
| GET | `/actuator/info` | Información del servicio |

`show-details` es `when_authorized`: sin autorización, el health devuelve solo el estado general, sin el detalle por componente. El health no incluye Kafka.

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

`spring.kafka.admin.auto-create` es `false`: el servicio no crea tópicos, porque eso lo hace solo kafka-admin. Arranca aunque Kafka no esté disponible: el listener se conecta cuando el broker responde.

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

## Tareas programadas

| Tarea | Cuándo | Qué hace |
|---|---|---|
| `ProcessedEventsCleanup` | 03:30 en `America/Santiago` | Borra las filas de `processed_events` del consumidor `report` con más de 30 días |

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

## Git

Ramas de tarea desde `develop` y PR hacia `develop`, según `barriodigita-info/convenciones.md`.
