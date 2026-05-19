package org.cibseven.getstarted.jobmonitor;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.cibseven.bpm.engine.impl.ProcessEngineImpl;
import org.cibseven.bpm.engine.impl.cmd.UnlockJobCmd;
import org.cibseven.bpm.engine.impl.jobexecutor.JobExecutor;
import org.cibseven.bpm.engine.impl.jobexecutor.RejectedJobsHandler;
import org.cibseven.bpm.engine.spring.components.jobexecutor.SpringJobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Single-file JobExecutor instrumentation:
 *
 *   - {@link InstrumentedTaskExecutor} exposes pool/queue state and tracks
 *     high-water marks for queue size and active threads.
 *   - {@link InstrumentedSpringJobExecutor} wraps every batch handed to the
 *     TaskExecutor with `BATCH SUBMITTED/START/DONE/FAILED/REJECTED` logs
 *     including the queue wait time and execution time.
 *   - {@link LoggingRejectedJobsHandler} dumps the full pool/queue state
 *     plus the activities currently held by each worker thread
 *     ({@link JobMonitorPlugin#currentlyRunning()}) on every overflow, then
 *     unlocks the jobs so acquisition retries them.
 *
 * Spring Boot Actuator picks up the camundaTaskExecutor bean automatically
 * (TaskExecutorMetricsAutoConfiguration) and exposes
 * executor.active / executor.queued / executor.pool.* on
 * /actuator/prometheus, so no separate MeterBinder needed.
 *
 * Pool/queue sizes come from camunda.bpm.job-execution.* properties.
 */
@Configuration
public class JobMonitoring {

  @Value("${camunda.bpm.job-execution.core-pool-size:3}")  private int corePoolSize;
  @Value("${camunda.bpm.job-execution.max-pool-size:10}")  private int maxPoolSize;
  @Value("${camunda.bpm.job-execution.queue-capacity:3}")  private int queueCapacity;
  @Value("${camunda.bpm.job-execution.max-jobs-per-acquisition:3}") private int maxJobsPerAcq;
  @Value("${camunda.bpm.job-execution.wait-time-in-millis:5000}")   private int waitMs;
  @Value("${camunda.bpm.job-execution.lock-time-in-millis:300000}") private int lockMs;

  @Bean(name = "camundaTaskExecutor", destroyMethod = "shutdown")
  public InstrumentedTaskExecutor camundaTaskExecutor() {
    InstrumentedTaskExecutor te = new InstrumentedTaskExecutor();
    te.setCorePoolSize(corePoolSize);
    te.setMaxPoolSize(maxPoolSize);
    te.setQueueCapacity(queueCapacity);
    te.setThreadNamePrefix("jobExecutor-");
    te.setRejectedExecutionHandler(new AbortPolicy());   // SpringJobExecutor expects the exception
    te.initialize();
    return te;
  }

  @Bean
  public JobExecutor jobExecutor(InstrumentedTaskExecutor te) {
    InstrumentedSpringJobExecutor je = new InstrumentedSpringJobExecutor();
    je.setTaskExecutor(te);
    je.setRejectedJobsHandler(new LoggingRejectedJobsHandler(te));
    je.setMaxJobsPerAcquisition(maxJobsPerAcq);
    je.setWaitTimeInMillis(waitMs);
    je.setLockTimeInMillis(lockMs);
    return je;
  }

  // ---------------------------------------------------------------------------

  public static class InstrumentedTaskExecutor extends ThreadPoolTaskExecutor {
    private final AtomicInteger queueHwm = new AtomicInteger();
    private final AtomicInteger activeHwm = new AtomicInteger();
    private final AtomicLong rejected = new AtomicLong();

    @Override public void execute(Runnable task) {
      queueHwm.accumulateAndGet(getQueueSize(), Math::max);
      activeHwm.accumulateAndGet(getActiveCount(), Math::max);
      try { super.execute(task); }
      catch (RuntimeException e) { rejected.incrementAndGet(); throw e; }
    }
    public int getQueueSize()      { var q = q(); return q == null ? 0 : q.size(); }
    public int getQueueRemaining() { var q = q(); return q == null ? 0 : q.remainingCapacity(); }
    public int getActiveCount()    { var t = getThreadPoolExecutor(); return t == null ? 0 : t.getActiveCount(); }
    public int getPoolSize()       { var t = getThreadPoolExecutor(); return t == null ? 0 : t.getPoolSize(); }
    public int getQueueHwm()       { return queueHwm.get(); }
    public int getActiveHwm()      { return activeHwm.get(); }
    public long getRejected()      { return rejected.get(); }
    private BlockingQueue<Runnable> q() { var t = getThreadPoolExecutor(); return t == null ? null : t.getQueue(); }
  }

  // ---------------------------------------------------------------------------

  public static class InstrumentedSpringJobExecutor extends SpringJobExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(InstrumentedSpringJobExecutor.class);

    @Override
    public void executeJobs(List<String> jobIds, ProcessEngineImpl engine) {
      String batchId = Integer.toHexString(System.identityHashCode(jobIds));
      long submitted = System.currentTimeMillis();
      Runnable original = getExecuteJobsRunnable(jobIds, engine);
      Runnable wrapped = () -> {
        MDC.put("batchId", batchId);
        long t0 = System.currentTimeMillis();
        LOG.info("BATCH START     batchId={} jobs={} thread={} waitMs={}",
            batchId, jobIds.size(), Thread.currentThread().getName(), t0 - submitted);
        try {
          original.run();
          LOG.info("BATCH DONE      batchId={} jobs={} thread={} execMs={}",
              batchId, jobIds.size(), Thread.currentThread().getName(),
              System.currentTimeMillis() - t0);
        } catch (Throwable t) {
          LOG.error("BATCH FAILED    batchId={} jobs={} execMs={} cause={}",
              batchId, jobIds.size(), System.currentTimeMillis() - t0, t.toString(), t);
          throw t;
        } finally { MDC.remove("batchId"); }
      };
      try {
        LOG.info("BATCH SUBMITTED batchId={} jobs={} jobIds={}", batchId, jobIds.size(), jobIds);
        getTaskExecutor().execute(wrapped);
      } catch (RejectedExecutionException rex) {
        LOG.error("BATCH REJECTED  batchId={} jobs={} reason={}", batchId, jobIds, rex.toString());
        getRejectedJobsHandler().jobsRejected(jobIds, engine, this);
      }
    }
  }

  // ---------------------------------------------------------------------------

  public static class LoggingRejectedJobsHandler implements RejectedJobsHandler {
    private static final Logger LOG = LoggerFactory.getLogger(LoggingRejectedJobsHandler.class);
    private final InstrumentedTaskExecutor te;
    private final AtomicLong totalRejected = new AtomicLong();

    public LoggingRejectedJobsHandler(InstrumentedTaskExecutor te) { this.te = te; }

    @Override
    public void jobsRejected(List<String> jobIds, ProcessEngineImpl engine, JobExecutor je) {
      totalRejected.addAndGet(jobIds.size());
      LOG.error("""
          JOBEXECUTOR QUEUE OVERFLOW
            jobIds          : {}
            totalRejected   : {}
            pool            : core={} active={} max={}
            queue           : {}/{} (remaining {})
            highWater       : queue={} active={}
            jdk-rejections  : {}
          Currently running (thread -> activity/PI):
          {}""",
          jobIds, totalRejected.get(),
          te.getCorePoolSize(), te.getActiveCount(), te.getMaxPoolSize(),
          te.getQueueSize(), te.getQueueCapacity(), te.getQueueRemaining(),
          te.getQueueHwm(), te.getActiveHwm(),
          te.getRejected(),
          ActivityTracker.currentlyRunning());

      // Unlock so the next acquisition cycle retries them — same as the
      // default NotifyAcquisitionRejectedJobsHandler does.
      var cmdExec = engine.getProcessEngineConfiguration().getCommandExecutorTxRequiresNew();
      for (String id : jobIds) {
        try { cmdExec.execute(new UnlockJobCmd(id)); }
        catch (Exception e) { LOG.warn("could not unlock job {}: {}", id, e.toString()); }
      }
    }
  }
}
