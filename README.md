# CIB seven JobExecutor Observability Demo

Spring Boot project based on
[`cibseven-get-started-spring-boot`](https://github.com/cibseven/cibseven-get-started-spring-boot)
that heavily instruments the **JobExecutor** (thread pool, queue,
acquisition, execution, rejection). When you suspect "the executor is
stuck" or "the queue is overflowing", you get the data to see exactly
what is happening and why.

What you get:

- **Logs** per activity start/end with MDC (`jobId`, `processInstanceId`,
  `activityId`, `batchId`) — to the console AND as JSON for Loki/ELK.
- **Logs** per job batch: `BATCH SUBMITTED` → `BATCH START` → `BATCH DONE`
  / `BATCH FAILED` / `BATCH REJECTED`, each with queue wait time and
  on-worker execution time.
- **Snapshot log** every 5 s with pool/queue occupancy, active threads,
  which process instance/activity is currently running where.
- **Diagnostic block** on every queue overflow with full state
  (rejected jobIds, pool/queue snapshot, high-water marks, all running
  activities, all in-flight batches).
- **Micrometer metrics** via `/actuator/prometheus`
  (`cibseven_jobexecutor_*`, `cibseven_engine_jobs_*`).
- **REST endpoints** under `/monitor/*` for ad-hoc queries.
- **Demo endpoint** `/demo/overflow` that pushes the pool over the edge
  on demand.

---

## Setup & start

```bash
# Java 17 and Maven are required
brew install openjdk@17 maven                 # if not present
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

git clone git@github.com:dominikhorn93/cibseven-threadpool-analyzing.git
cd cibseven-threadpool-analyzing
mvn -DskipTests package
java -jar target/jobexecutor-observability-0.0.1-SNAPSHOT.jar
```

Default port `8080`, login `demo / demo`.

> The **JWT secret** in `cibseven-webclient.properties` is a placeholder
> for local use. Regenerate before deploying anywhere real:
> `openssl rand -base64 130 | tr -d '\n'`.

Important URLs:

| URL | Content |
|---|---|
| `http://localhost:8080/cibseven/app/cockpit/` | CIB seven Cockpit |
| `http://localhost:8080/actuator/health` | Health (incl. `jobExecutor` sub-health) |
| `http://localhost:8080/actuator/prometheus` | All metrics |
| `http://localhost:8080/monitor/snapshot` | Full JSON snapshot |
| `http://localhost:8080/monitor/threads` | What each worker thread is doing |
| `http://localhost:8080/monitor/batches` | In-flight batches (TaskExecutor view) |
| `http://localhost:8080/monitor/history` | Ring buffer of completed / rejected batches |
| `http://localhost:8080/monitor/queue` | Plain pool/queue view |
| `http://localhost:8080/monitor/db-jobs` | Jobs in the engine DB |

---

## Architecture

```
JobMonitorApplication           Boot main, @EnableScheduling
│
├── config/
│   └── JobExecutorConfig       overrides the starter's default beans:
│                                  - camundaTaskExecutor → InstrumentedTaskExecutor
│                                  - jobExecutor         → InstrumentedSpringJobExecutor
│                                                          + LoggingRejectedJobsHandler
│
├── monitoring/
│   ├── ThreadContextRegistry        central view of "what is running where"
│   ├── ThreadJobContext             snapshot of one running activity
│   ├── JobBatchInfo                 lifecycle of one job batch
│   │
│   ├── JobMonitorEnginePlugin       SpringBootProcessEnginePlugin → preInit
│   ├── JobMonitorBpmnParseListener  attaches an ExecutionListener to every activity
│   ├── JobMonitorExecutionListener  maintains registry + MDC on start/end
│   │
│   ├── InstrumentedTaskExecutor     extends ThreadPoolTaskExecutor with
│   │                                queue/active high-water marks, submit/reject counters
│   ├── InstrumentedSpringJobExecutor    wraps each ExecuteJobsRunnable with
│   │                                a batch ID + logs + registry updates
│   ├── LoggingRejectedJobsHandler   CIB seven RejectedJobsHandler with a
│   │                                detailed diagnostic block; unlocks jobs
│   │
│   ├── JobExecutorMetricsBinder     Micrometer gauges for everything
│   └── JobExecutorScheduledLogger   periodic snapshot to the log
│
├── delegate/
│   └── SlowDelegate                 fake service logic (sleep + optional failure)
│
└── web/
    ├── LoadGeneratorController       POST /demo/overflow → generate load
    └── JobMonitorController          GET  /monitor/*     → query state
```

---

## How the tracking works

CIB seven (a Camunda 7 fork) runs one **job** per asynchronous activity.
The `JobExecutor` does this:

1. **Acquisition thread** (engine-internal): periodically polls the DB
   for ready-to-execute jobs and locks up to
   `maxJobsPerAcquisition` of them.
2. Hands the list to `SpringJobExecutor.executeJobs(jobIds, engine)`
   → `taskExecutor.execute(new ExecuteJobsRunnable(jobIds, engine))`.
3. Spring's `ThreadPoolTaskExecutor` parks the runnable in the pool (or
   its queue). When both are full → `RejectedExecutionException` →
   `RejectedJobsHandler.jobsRejected(...)`.
4. On a worker thread `ExecuteJobsRunnable.run()` executes the jobs in
   sequence → invokes activity logic (delegate / listener).

We hook in at **three** places:

| Hook | What it records |
|---|---|
| `InstrumentedSpringJobExecutor.executeJobs` | mints a `batchId`, logs `BATCH SUBMITTED`, wraps the runnable. |
| Wrapped runnable on the worker thread | `BATCH START` / `BATCH DONE`, sets MDC `batchId`. |
| `JobMonitorExecutionListener.notify(START/END)` | fills `ThreadContextRegistry` and MDC with `jobId/pi/activity/businessKey`. |

This gives you, at **any** point in time, the answer to "which thread,
which batch (with which jobIds), which activity of which PI?".

---

## Provoking queue overflow

`application.yaml` keeps the pool small on purpose so the demo bites
quickly:

```yaml
camunda.bpm.job-execution:
  core-pool-size: 3
  max-pool-size: 3
  queue-capacity: 5
  max-jobs-per-acquisition: 3
```

Generate load:

```bash
# 30 process instances, each activity sleeps for 4 seconds
curl -X POST 'http://localhost:8080/demo/overflow?count=30&sleepMs=4000'
```

---

## What you see — and how to read it

### 1. Snapshot block (every 5 s, into the log)

```
--- JobExecutor Snapshot --------------------------------------------------
pool   active=3/3/3    queue=5/5 (hwm queue=5, hwm active=3)
events submitted=13349 completed=0 rejectedEvents=13341 rejectedJobs=13341
engine jobsPending(DB)=20 jobsDue(DB)=20
Live activities:
    jobExecutor-1   job=a387fd64… pi=a385654e… activity=prepare ageMs=2423
    jobExecutor-2   job=a389ab1c… pi=a3895cf6… activity=prepare ageMs=2423
    jobExecutor-3   job=a38a9584… pi=a38a6e6e… activity=prepare ageMs=2419
Live batches:
    batch=3c202cef state=RUNNING thread=jobExecutor-2 waitMs=0    execMs=2425  jobs=[a389ab1c-…]
    batch=841834ad state=QUEUED  thread=null          waitMs=2412 execMs=0     jobs=[a38bf527-…]
    batch=7cd69122 state=QUEUED  thread=null          waitMs=2408 execMs=0     jobs=[a38cb87f-…]
```

**Reading it:**

- `pool active=3/3/3` → 3 workers running, pool size 3, max 3. Pool
  fully saturated.
- `queue=5/5` → Spring queue full as well. **Any further submit will
  be rejected.**
- `hwm queue=5, hwm active=3` → at some point since startup both limits
  were touched. Even if the pool looks relaxed now, the high-water mark
  reveals there were tight moments.
- `events submitted=13349 completed=0` → 13349 hand-offs to the
  TaskExecutor, **zero** of them finished. → classic picture of "pool
  is stuck, acquisition is hammering" when the rejection rate is high.
- `rejectedEvents=13341 rejectedJobs=13341` → acquisition keeps locking
  jobs that all get rejected. This **is normal** under overflow — the
  engine locks, submits, gets rejected, our `RejectedJobsHandler`
  unlocks them, next round repeats. A high rejection rate **is not the
  problem, it is the symptom**: the workers are sitting in their
  current activity for too long.
- `jobsPending(DB)=20 jobsDue(DB)=20` → 20 jobs waiting in the database,
  all currently due. The engine cannot drain them.
- `Live activities` — **the most important field.** You see every
  worker, the PI/activity occupying it, and for how long (`ageMs`). In
  the example above three workers have been stuck in `activity=prepare`
  of the `overflowDemo` PIs for ~2.4 s. → when `ageMs` grows
  unexpectedly large for some activity, that is your culprit.
- `Live batches` — TaskExecutor view. `state=RUNNING` means the batch
  is on a worker, `state=QUEUED` means it sits in the Spring queue.
  `waitMs` is queue wait time, `execMs` is execution time. → many
  `QUEUED` rows with rising `waitMs` is the "queue is backing up"
  signal.

### 2. Detailed block on every rejected batch

```
JOBEXECUTOR QUEUE OVERFLOW
  rejected jobIds : [81161642-52e6-11f1-8f8b-e2e34a5c8abc]
  rejected count  : 1 (total since start: 1)
TaskExecutor state
  poolSize        : 3 (core=3, max=3)
  activeThreads   : 3
  queue           : 5/5 (remaining capacity: 0)
  highWater queue : 5
  highWater active: 3
  rejected events : 1
JobExecutor (CIB seven)
  name            : JobExecutor[…InstrumentedSpringJobExecutor]
  maxJobsPerAcq   : 3
  waitTimeMs      : 5000
Currently running (thread → activity/PI):
  - jobExecutor-1   job=810ffb7f-… pi=810db189-… activity=prepare ageMs=30
  - jobExecutor-2   job=81113407-… pi=81110cf1-… activity=prepare ageMs=30
  - jobExecutor-3   job=8111d04f-… pi=8111a939-… activity=prepare ageMs=26
In-flight batches (accepted by TaskExecutor):
  - batch=641cda07 state=RUNNING thread=jobExecutor-1 waitMs=1 execMs=33 jobs=[810ffb7f-…]
  - batch=0c8c4970 state=QUEUED  thread=null          waitMs=22 execMs=0  jobs=[81161642-…]
  …
```

**Reading it:**

- Exact job IDs that were just rejected (`rejected jobIds`).
- Full pool/queue state **at the moment of rejection** (not averaged) —
  this tells you:
  - `queue=full` AND `pool=max` AND `activeThreads=max` → genuine
    overflow. Adding pool/queue capacity only buys time.
  - `queue=full` BUT `activeThreads < max` → JDK pool prefers filling
    the queue before scaling beyond `core` (standard
    `ThreadPoolExecutor` behaviour). Irrelevant if `core=max`.
- **Table of running activities** — the actual "what caused this".
  Three threads stuck for minutes in the same activity (big `ageMs`)
  identify the bottleneck.
- **In-flight batches** with status — you see which batches were
  already in the queue AND which are running. Useful to tell whether
  the queue is "really" full (workers slow) or "artificially" full
  (acquisition pushes faster than workers drain).

### 3. Per-activity logs (with MDC)

```
20:21:23.404 INFO  [jobExecutor-1] 8111d04f-… 8111a939-… prepare …JobMonitorExecutionListener - ACTIVITY START  job=8111d04f-… jobType=async-continuation activity=prepare pi=8111a939-… thread=jobExecutor-1
20:21:23.405 INFO  [jobExecutor-1] 8111d04f-… 8111a939-… prepare …SlowDelegate                 - slowDelegate sleep 4000 ms (activity=prepare, pi=8111a939-…)
20:21:27.406 INFO  [jobExecutor-1] 8111d04f-… 8111a939-… prepare …JobMonitorExecutionListener - ACTIVITY END    activity=prepare pi=8111a939-… thread=jobExecutor-1 durationMs=4001
```

**Reading it:** the pattern is `HH:mm:ss.SSS LEVEL [thread] jobId pi activity logger - msg`.
Filter by `pi=<id>` and you see every activity step **of this single**
process instance regardless of which worker thread executed it. If you
ship JSON to Loki/ELK, the same fields are structured properties in
`logs/jobmonitor*.json`.

### 4. Prometheus metrics

```
cibseven_jobexecutor_pool_active{executor="spring-job-executor"}   3.0
cibseven_jobexecutor_pool_size{…}                                  3.0
cibseven_jobexecutor_pool_max{…}                                   3.0
cibseven_jobexecutor_queue_size{…}                                 5.0
cibseven_jobexecutor_queue_capacity{…}                             5.0
cibseven_jobexecutor_queue_remaining{…}                            0.0
cibseven_jobexecutor_queue_highwater{…}                            5.0
cibseven_jobexecutor_active_highwater{…}                           3.0
cibseven_jobexecutor_submitted{…}                                  7844.0
cibseven_jobexecutor_completed{…}                                  0.0
cibseven_jobexecutor_rejected_events{…}                            7836.0
cibseven_jobexecutor_rejected_jobs{…}                              7836.0
cibseven_jobexecutor_live_threads{…}                               3.0
cibseven_jobexecutor_live_batches{…}                               8.0
cibseven_jobexecutor_acquisition_batchSize{…}                      3.0
cibseven_jobexecutor_acquisition_waitMs{…}                         5000.0
cibseven_engine_jobs_pending                                       30.0
cibseven_engine_jobs_due                                           30.0
```

**Useful Grafana alerts:**

| Alert | Condition |
|---|---|
| Pool permanently at the limit | `cibseven_jobexecutor_pool_active == cibseven_jobexecutor_pool_max` for > 1 min |
| Queue permanently full | `cibseven_jobexecutor_queue_remaining == 0` for > 30 s |
| Rejection rate rising | `rate(cibseven_jobexecutor_rejected_jobs[1m]) > 0` |
| Throughput gone | `rate(cibseven_jobexecutor_completed[5m]) == 0` AND `cibseven_jobexecutor_pool_active > 0` (= workers running but nothing completes → long-running job) |
| DB backlog | `cibseven_engine_jobs_due` rising monotonically |

---

## Symptom → where to look

| Symptom | Where |
|---|---|
| "Executor is stuck" | `/monitor/threads` or the snapshot block. A high `ageMs` on an activity = that worker is hanging there. The delegate is the culprit. |
| "Queue is overflowing" | The `JOBEXECUTOR QUEUE OVERFLOW` block — has rejected jobIds, pool/queue state, live activities. Metric `cibseven_jobexecutor_rejected_jobs`. |
| "Jobs aren't being processed" | Compare `cibseven_engine_jobs_pending` vs `_due`. `/monitor/db-jobs` for detail (`suspended`? `retries=0`? `exceptionMessage`?). |
| "Throughput too low" | Compare `submitted` vs `completed` counters. Tune `acquisition.batchSize` and `acquisition.waitMs`. |
| "Deadlock / lock contention" | Acquisition-thread logs (`org.cibseven.bpm.engine.jobexecutor=DEBUG` is already enabled). |
| "Which PI/job is blocking?" | "Currently running" table in the snapshot/overflow block, or `/monitor/threads` as JSON. |
| "What was in the backlog at time X?" | `/monitor/history` ring buffer (200 entries, configurable via `jobmonitor.history-ring-size`). |

---

## Tuning notes for production

- Set `core-pool-size` = `max-pool-size`. Growing the pool on demand
  rarely improves throughput in practice and adds lock contention and
  cache-locality problems.
- Keep `queue-capacity` small. A big queue only hides that acquisition
  is too aggressive. Lower `max-jobs-per-acquisition` and let the
  `RejectedJobsHandler` kick in earlier — it unlocks jobs immediately.
- Don't make `lock-time-in-millis` too short. A short lock can expire
  while a long-running job is still executing, allowing another engine
  in the cluster to grab the same job.
- In production either keep the `JobExecutorScheduledLogger` running
  (control via log level) or raise the interval
  (`jobmonitor.snapshot-log-interval-seconds`).
- The JSON logs (`logs/jobmonitor*.json`) are directly Loki/ELK
  ingestible — `batchId`, `jobId`, `processInstanceId` are first-class
  fields, not just inside the message.

---

## Extension ideas

- Mix BPMN with timer + async-after + async-before to demonstrate how
  different JobHandler types share the same pool.
- A second engine instance in a cluster → demonstrates `lockOwner` and
  acquisition distribution.
- Grafana dashboard JSON for the `cibseven_jobexecutor_*` metrics.
- Distributed tracing (OpenTelemetry) instead of MDC for cross-service
  correlation.

---

## Things that needed fixing during initial setup

During the first build/start a few real incompatibilities with the
CIB seven 2.1.0 API and a few config requirements surfaced. They are
all fixed in the sources here, listed for reference if you ever move
this project to another version:

1. `SpringBootProcessEnginePlugin` lives under
   `org.cibseven.bpm.spring.boot.starter.util` (not `.plugin`).
2. `JobExecutor.executeJobs(jobIds, engine)` is **public** — overrides
   must also be `public`.
3. `@Bean` methods that return an interface hide the concrete type from
   the autowiring resolver → declare the concrete type or use
   `@Qualifier`.
4. Spring's `taskScheduler` (from `@EnableScheduling`) also implements
   `TaskExecutor`. When you need a specific one, inject the concrete
   type to disambiguate.
5. `cibseven-webclient` requires a JWT secret ≥155 chars, base64-decodable
   — otherwise `BaseUserProvider` throws `IllegalArgumentException`. A
   test value lives in `src/main/resources/cibseven-webclient.properties`;
   regenerate for production via `openssl rand -base64 130 | tr -d '\n'`.
6. Process Engine since Camunda 7.16 requires a default
   `historyTimeToLive` — no default means no deployment. Set it in
   `application.yaml`:
   `camunda.bpm.generic-properties.properties.historyTimeToLive: P30D`.
7. Spring MVC `@RequestParam(defaultValue=...)` without explicit name
   needs the `-parameters` compiler flag
   (`<parameters>true</parameters>` on the `maven-compiler-plugin`).
