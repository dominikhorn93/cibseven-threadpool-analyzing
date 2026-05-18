# CIB seven JobExecutor Observability

Spring Boot demo (based on
[`cibseven-get-started-spring-boot`](https://github.com/cibseven/cibseven-get-started-spring-boot))
that makes the JobExecutor fully observable: which thread runs which
job for which process instance, queue/pool state, batch lifecycle,
overflow diagnostics. Includes a load generator to provoke the
problem on purpose.

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

Boots on `http://localhost:8080`, login `demo / demo`.

The pool is intentionally tiny (`pool=3`, `queue=5`,
`max-jobs-per-acquisition=3` in `application.yaml`) so the queue
overflows quickly in the demo.

## Trigger an overflow

```bash
curl -X POST 'http://localhost:8080/demo/overflow?count=30&sleepMs=4000'
```

Starts 30 process instances of `overflowDemo`. Each PI emits 1 + 5 = 6
async jobs. With pool=3 / queue=5 the queue saturates within seconds.

## Where to look

| Source | What it shows |
|---|---|
| `app.log` (console) | live `BATCH SUBMITTED/START/DONE`, `ACTIVITY START/END`, `JOBEXECUTOR QUEUE OVERFLOW` blocks, periodic snapshot block |
| `logs/jobmonitor*.json` | same events as structured JSON for Loki/ELK |
| `GET /monitor/snapshot` | full state as JSON |
| `GET /monitor/threads` | per-thread: which PI/activity/job is running |
| `GET /monitor/batches` | batches handed to the TaskExecutor |
| `GET /monitor/history` | ring buffer of completed / rejected batches |
| `GET /monitor/db-jobs` | jobs in the engine DB (retries, exceptions, suspended) |
| `GET /actuator/prometheus` | metrics, all prefixed `cibseven_jobexecutor_` / `cibseven_engine_jobs_` |

## Reading the two key log blocks

**Snapshot (every 5 s):**

```
pool   active=3/3/3    queue=5/5 (hwm queue=5, hwm active=3)
events submitted=13349 completed=0 rejectedEvents=13341 rejectedJobs=13341
engine jobsPending(DB)=20 jobsDue(DB)=20
Live activities:
    jobExecutor-1   job=a387fd64… pi=a385654e… activity=prepare ageMs=2423
    jobExecutor-2   job=a389ab1c… pi=a3895cf6… activity=prepare ageMs=2423
    jobExecutor-3   job=a38a9584… pi=a38a6e6e… activity=prepare ageMs=2419
```

- `active=3/3/3` and `queue=5/5` → pool + queue saturated.
- `submitted` ≫ `completed` → acquisition pushes faster than workers
  drain → high rejection rate is the **symptom**, not the cause.
- The `ageMs` of each `Live activity` is the smoking gun: a slow
  delegate sitting on `activity=prepare` for >2 s is what's blocking
  the workers.

**Overflow (printed for every rejected batch):**

```
JOBEXECUTOR QUEUE OVERFLOW
  rejected jobIds : [81161642-…]
  queue           : 5/5 (remaining capacity: 0)
  highWater queue : 5
  highWater active: 3
Currently running (thread -> activity/PI):
  - jobExecutor-1   job=810ffb7f-… pi=810db189-… activity=prepare ageMs=30
  - jobExecutor-2   job=81113407-… pi=81110cf1-… activity=prepare ageMs=30
  - jobExecutor-3   job=8111d04f-… pi=8111a939-… activity=prepare ageMs=26
In-flight batches (accepted by the TaskExecutor):
  - batch=641cda07 state=RUNNING thread=jobExecutor-1 waitMs=1  execMs=33 jobs=[810ffb7f-…]
  - batch=0c8c4970 state=QUEUED  thread=null          waitMs=22 execMs=0  jobs=[81161642-…]
```

Tells you exactly which jobIds were rejected, the full pool/queue
state **at that moment**, and the activities that caused the backup.
After logging, the handler unlocks the jobs so the next acquisition
cycle retries them.

## How to drop this into your own project

Five classes carry the whole thing — copy them and the matching
`@Configuration`:

```
config/JobExecutorConfig.java                          # replaces starter beans
monitoring/InstrumentedTaskExecutor.java               # pool + queue counters
monitoring/InstrumentedSpringJobExecutor.java          # batch lifecycle
monitoring/LoggingRejectedJobsHandler.java             # overflow diagnostic + unlock
monitoring/JobMonitorEnginePlugin.java
monitoring/JobMonitorBpmnParseListener.java            # attach ExecutionListener
monitoring/JobMonitorExecutionListener.java            # thread -> activity mapping
monitoring/ThreadContextRegistry.java                  # central registry
```

Plus the supporting bits if you want them:

- `JobExecutorMetricsBinder` — Micrometer gauges
- `JobExecutorScheduledLogger` — periodic snapshot to the log
- `JobMonitorController` — REST inspection endpoints

The `JobExecutorConfig` overrides the starter's `camundaTaskExecutor`
and `jobExecutor` beans (both `@ConditionalOnMissingBean` in the
starter), so simply pulling these classes into a Spring Boot
application picks them up. Add `@EnableScheduling` on your boot
class if you want the periodic snapshot.

## Things to watch when adapting

These bit me building this and will likely bite anyone reproducing
on top of cibseven 2.1.0:

1. `SpringBootProcessEnginePlugin` lives in
   `org.cibseven.bpm.spring.boot.starter.util` (not `.plugin`).
2. `JobExecutor.executeJobs(...)` is **public** — overrides must
   match the visibility.
3. `@Bean` methods returning an interface hide the concrete type from
   the autowiring resolver. Declare the concrete subtype if other
   beans need to inject it directly.
4. Spring's `taskScheduler` (from `@EnableScheduling`) also implements
   `TaskExecutor` — disambiguate by concrete type.
5. `cibseven-webclient` requires a base64 JWT secret ≥ 155 chars
   (`BaseUserProvider.checkKey`). The placeholder in
   `cibseven-webclient.properties` is for local testing only;
   regenerate before deploying:
   `openssl rand -base64 130 | tr -d '\n'`.
6. Process Engine since 7.16 requires a default `historyTimeToLive`:
   `camunda.bpm.generic-properties.properties.historyTimeToLive: P30D`.
7. Spring MVC `@RequestParam(defaultValue=...)` without explicit name
   needs `<parameters>true</parameters>` on the `maven-compiler-plugin`.

## Tuning hint

Don't fix overflow by growing the pool — find which activity is
holding a worker for too long (the `ageMs` in the `Live activities`
table is your starting point). Larger pools mostly add lock
contention. Lower `max-jobs-per-acquisition` and let the
`RejectedJobsHandler` (which unlocks immediately) regulate the
load.
