# CIB seven JobExecutor Observability

Spring Boot demo (based on
[`cibseven-get-started-spring-boot`](https://github.com/cibseven/cibseven-get-started-spring-boot))
that turns the JobExecutor inside-out: every batch, every activity,
every overflow becomes a log line tagged with `thread`, `jobId`,
`processInstanceId`, `activityId`. ~315 LOC in three Java files —
the per-activity hook uses the cibseven starter's built-in
`EventPublisherPlugin` (`@EventListener(ExecutionEvent)`), so no
custom `BpmnParseListener` boilerplate.

## Run

```bash
brew install openjdk@17 maven
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

git clone git@github.com:dominikhorn93/cibseven-threadpool-analyzing.git
cd cibseven-threadpool-analyzing
mvn -DskipTests package
java -jar target/jobexecutor-observability-0.0.1-SNAPSHOT.jar
```

App at `http://localhost:8080`, login `demo / demo`.
Pool is intentionally tiny (`pool=3`, `queue=5` in `application.yaml`)
so the overflow scenario hits in seconds.

## Test it

```bash
# Light load — workers run normally, you see per-activity tracking
curl -X POST 'http://localhost:8080/demo/overflow?count=3&sleepMs=2000'

# Heavy load — queue overflows, rejection diagnostic kicks in
curl -X POST 'http://localhost:8080/demo/overflow?count=30&sleepMs=4000'
```

Watch the console output (or `logs/jobmonitor.json`).

## What you see

**1. Every BPMN activity logs start + end with full context (DEBUG):**

```
DEBUG [jobExecutor-3] … ACTIVITY START job=4de1844f-… pi=4de15d3b-… activity=prepare thread=jobExecutor-3
DEBUG [jobExecutor-3] … Currently running (3):
  - jobExecutor-1  job=4ddf8873-… pi=4ddcf05f-… activity=prepare ageMs=5
  - jobExecutor-2  job=4de0e809-… pi=4de0c0f5-… activity=prepare ageMs=5
  - jobExecutor-3  job=4de1844f-… pi=4de15d3b-… activity=prepare ageMs=0
DEBUG [jobExecutor-3] … ACTIVITY END   pi=4de15d3b-… activity=prepare thread=jobExecutor-3 durationMs=2003
```

→ Each activity event is followed by the **full snapshot of everything
currently in flight** — you can read the state at any timestamp without
waiting for a rejection. A thread that emits `ACTIVITY START` and never
the matching `END` is your hanger.

> Logger: `org.cibseven.getstarted.jobmonitor` at `DEBUG` (already set in
> `application.yaml`). In production raise it to `INFO` and this channel
> goes silent — the overflow diagnostic (ERROR) still fires.

**2. Every job batch logs its lifecycle through the TaskExecutor:**

```
BATCH SUBMITTED batchId=12ab jobs=3 jobIds=[8af43062-…, 8af43063-…, 8af43064-…]
BATCH START     batchId=12ab jobs=3 thread=jobExecutor-1 waitMs=4
BATCH DONE      batchId=12ab jobs=3 thread=jobExecutor-1 execMs=6012
```

→ Compare `waitMs` (time spent in the Spring queue) with `execMs`. A
big `waitMs` means workers are slower than acquisition pushes.

**3. On every queue overflow, a full diagnostic block:**

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

→ The `Currently running` table identifies the bottleneck activity at
the moment of rejection. Rejected jobs are auto-unlocked for the next
acquisition cycle.

**4. Pool / queue metrics for Prometheus:**

```bash
curl -s http://localhost:8080/actuator/prometheus | grep '^executor_'
```

```
executor_active_threads{name="camundaTaskExecutor"}          3.0
executor_queued_tasks{name="camundaTaskExecutor"}            5.0
executor_queue_remaining_tasks{name="camundaTaskExecutor"}   0.0
executor_pool_size_threads{name="camundaTaskExecutor"}       3.0
```

→ Auto-bound by Spring Actuator; no custom MeterBinder needed.

## Files

```
src/main/java/org/cibseven/getstarted/jobmonitor/
├── JobMonitoring.java          @Configuration + TaskExecutor +
│                               SpringJobExecutor + RejectedJobsHandler
├── ActivityTracker.java        @EventListener(ExecutionEvent) — picks
│                               up the events the starter's
│                               EventPublisherPlugin already publishes
└── JobMonitorApplication.java  boot main, SlowDelegate, /demo/overflow
```

## Use it in your own project

Copy the three files; the `@Configuration` overrides the cibseven
starter's `camundaTaskExecutor` and `jobExecutor` beans (both
`@ConditionalOnMissingBean`), so wiring is automatic. The logback
pattern in `logback-spring.xml` renders the MDC keys — pick it up
or merge it with yours:

```xml
<pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %X{jobId:-} %X{processInstanceId:-} %X{activityId:-} %logger{36} - %msg%n</pattern>
```

## Gotchas adapting to CIB seven 2.1.0

1. `JobExecutor.executeJobs(...)` is **public** — match the visibility
   on the override.
2. The activity event hook uses
   `org.cibseven.bpm.spring.boot.starter.event.ExecutionEvent`. The
   starter's `EventPublisherPlugin` publishes it automatically; defaults
   for `camunda.bpm.eventing.execution/task/history` are all `true`.
3. `cibseven-webclient` needs a base64 JWT secret ≥ 155 chars
   (`BaseUserProvider.checkKey`). The value in
   `cibseven-webclient.properties` is for local use only; regenerate:
   `openssl rand -base64 130 | tr -d '\n'`.
4. Engine since 7.16 requires a default `historyTimeToLive`:
   `camunda.bpm.generic-properties.properties.historyTimeToLive: P30D`.
5. `@RequestParam(defaultValue=...)` without explicit name needs
   `<parameters>true</parameters>` on the `maven-compiler-plugin`.

## Tuning hint

Don't fix overflow by growing the pool — find the activity that holds
a worker for too long (`durationMs` in `ACTIVITY END`, or `ageMs` in
the overflow block). Larger pools mostly add lock contention.
Lowering `max-jobs-per-acquisition` makes the `RejectedJobsHandler`
regulate load earlier (it unlocks immediately).
