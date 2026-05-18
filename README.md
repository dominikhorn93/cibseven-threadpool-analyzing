# CIB seven JobExecutor Observability

Spring Boot demo (based on
[`cibseven-get-started-spring-boot`](https://github.com/cibseven/cibseven-get-started-spring-boot))
that makes the JobExecutor observable in three files:

- `JobMonitoring.java` — instrumented `SpringJobExecutor` + `TaskExecutor`
  + `RejectedJobsHandler` (~170 LOC, one `@Configuration`).
- `JobMonitorPlugin.java` — `ProcessEnginePlugin` that attaches an
  `ExecutionListener` to every BPMN activity so each worker thread
  can be tagged with the current job / PI / activity (~110 LOC).
- `JobMonitorApplication.java` — boot main + `SlowDelegate` + load
  generator endpoint (~55 LOC).

Spring Actuator picks up the `camundaTaskExecutor` bean automatically
and exposes the standard `executor_*` metrics — no separate
`MeterBinder` needed.

## Run it

```bash
brew install openjdk@17 maven
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

git clone git@github.com:dominikhorn93/cibseven-threadpool-analyzing.git
cd cibseven-threadpool-analyzing
mvn -DskipTests package
java -jar target/jobexecutor-observability-0.0.1-SNAPSHOT.jar
```

`http://localhost:8080`, login `demo / demo`. The pool is intentionally
tiny in `application.yaml` (`pool=3`, `queue=5`,
`max-jobs-per-acquisition=3`) so the demo overflows fast.

## Trigger an overflow

```bash
curl -X POST 'http://localhost:8080/demo/overflow?count=30&sleepMs=4000'
```

Starts 30 instances of `overflowDemo`; each emits 1 + 5 = 6 async jobs.

## Where to look

| Source | What it shows |
|---|---|
| `app.log` | `BATCH SUBMITTED/START/DONE/REJECTED` per batch + `JOBEXECUTOR QUEUE OVERFLOW` block on every reject |
| `logs/jobmonitor*.json` | same events as structured JSON |
| `GET /actuator/prometheus` | Spring Actuator: `executor_active_threads`, `executor_queued_tasks`, `executor_queue_remaining_tasks`, `executor_pool_size_threads`, … (tag `name="camundaTaskExecutor"`) |

## What the overflow block tells you

```
JOBEXECUTOR QUEUE OVERFLOW
  jobIds          : [8af43062-…]
  totalRejected   : 1
  pool            : core=3 active=3 max=3
  queue           : 5/5 (remaining 0)
  highWater       : queue=5 active=3
  jdk-rejections  : 1
Currently running (thread -> activity/PI):
  - jobExecutor-1  job=8aee8adf-… pi=8aebf2cb-… activity=prepare ageMs=24
  - jobExecutor-2  job=8aefc365-… pi=8aef7541-… activity=prepare ageMs=24
  - jobExecutor-3  job=8af05fab-… pi=8af03897-… activity=prepare ageMs=18
```

- `pool active=max` + `queue=full` → genuine saturation.
- `highWater` survives even if the pool looks relaxed later.
- The **Currently running** table tells you which activity holds each
  worker — high `ageMs` on the same activity across all workers
  identifies the bottleneck.

After logging, the handler unlocks the rejected jobs so the next
acquisition cycle retries them (same behaviour as the default
`NotifyAcquisitionRejectedJobsHandler`).

## How to drop this into your own project

Copy the three Java files into your package and a Logback pattern that
includes the MDC keys:

```xml
<pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %X{jobId:-} %X{processInstanceId:-} %X{activityId:-} %logger{36} - %msg%n</pattern>
```

The `JobMonitoring` `@Configuration` overrides the two
`@ConditionalOnMissingBean` beans the cibseven-bpm-spring-boot starter
provides (`camundaTaskExecutor` and `jobExecutor`), so the wiring is
automatic.

## Things to watch when adapting (CIB seven 2.1.0)

1. `SpringBootProcessEnginePlugin` lives in
   `org.cibseven.bpm.spring.boot.starter.util` (not `.plugin`).
2. `JobExecutor.executeJobs(...)` is **public** — match it on the override.
3. `@Bean` methods returning an interface hide the concrete type from
   the autowiring resolver. Declare the concrete subtype if other
   beans need to inject it directly.
4. Spring's `taskScheduler` (from `@EnableScheduling`) also implements
   `TaskExecutor` — disambiguate by concrete type when injecting.
5. `cibseven-webclient` requires a base64 JWT secret ≥ 155 chars
   (`BaseUserProvider.checkKey`). The placeholder in
   `cibseven-webclient.properties` is for local testing only;
   regenerate: `openssl rand -base64 130 | tr -d '\n'`.
6. Process Engine since 7.16 requires a default `historyTimeToLive`:
   `camunda.bpm.generic-properties.properties.historyTimeToLive: P30D`.
7. Spring MVC `@RequestParam(defaultValue=...)` without explicit name
   needs `<parameters>true</parameters>` on the `maven-compiler-plugin`.

## Tuning hint

Don't fix overflow by growing the pool — find the activity that holds
a worker for too long (`ageMs` in the **Currently running** table).
Larger pools mostly add lock contention. Lowering
`max-jobs-per-acquisition` makes the `RejectedJobsHandler` regulate
load earlier (it unlocks immediately).
