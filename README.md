# ms-barriodigital-report

Microservicio de reportería de **BarrioDigital**: KPIs y plazos de los trámites.

Puerto: **8086**

## Responsabilidad

Se conecta a Oracle Autonomous mediante wallet y expone el health de Actuator. El puerto y la API del servicio se definen en el contrato EP3-07 de `barriodigital-infra/docs/decisiones.md`.

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/actuator/health` | Estado del servicio, incluida la base de datos (`db`) |
| GET | `/actuator/info` | Información del servicio |

`show-details` es `when_authorized`: sin autorización, el health devuelve solo el estado general, sin el detalle por componente. El health no incluye Kafka.

## Stack

- Java 21 y Spring Boot 3.3.5
- `spring-kafka`, `spring-boot-starter-data-jpa`, `spring-boot-starter-web` y `spring-boot-starter-actuator`
- Oracle Autonomous con `ojdbc11` y wallet (`oraclepki`, `osdt_core`, `osdt_cert`)
- H2 solo en tests

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

El servicio comparte el esquema de Oracle con los demás servicios de BarrioDigital. No define entidades, así que no crea tablas.

## Kafka

`spring.kafka.admin.auto-create` es `false`: el servicio no crea tópicos, porque eso lo hace solo kafka-admin. No abre conexiones a Kafka al arrancar, así que arranca aunque Kafka no esté disponible.

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

Los tests usan H2 en modo Oracle, sin Oracle ni Kafka reales:

- `ReportApplicationTest`: el contexto arranca con el puerto 8086, el nombre del servicio y sin creación automática de tópicos.
- `OracleWalletConfigurationTest`: sin variable no hace nada; una carpeta inexistente o sin `tnsnames.ora` impide arrancar; un wallet válido fija `oracle.net.tns_admin`.

## Git

Ramas de tarea desde `develop` y PR hacia `develop`, según `barriodigita-info/convenciones.md`.
