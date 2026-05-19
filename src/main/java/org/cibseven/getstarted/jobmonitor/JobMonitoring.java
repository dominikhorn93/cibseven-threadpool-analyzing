package org.cibseven.getstarted.jobmonitor;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.atomic.AtomicLong;
import org.cibseven.bpm.engine.impl.ProcessEngineImpl;
import org.cibseven.bpm.engine.impl.cmd.UnlockJobCmd;
import org.cibseven.bpm.engine.impl.jobexecutor.JobExecutor;
import org.cibseven.bpm.engine.impl.jobexecutor.RejectedJobsHandler;
import org.cibseven.bpm.engine.spring.components.jobexecutor.SpringJobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Overrides the two {@code @ConditionalOnMissingBean} beans the
 * cibseven-bpm-spring-boot starter provides, so we can plug in our own
 * {@link LoggingRejectedJobsHandler}. Otherwise everything is stock:
 *
 *   - {@code camundaTaskExecutor} is a plain {@link ThreadPoolTaskExecutor}.
 *     Spring Actuator binds it automatically and exposes
 *     {@code executor_active_threads}, {@code executor_queued_tasks},
 *     {@code executor_queue_remaining_tasks}, {@code executor_pool_size_threads}
 *     on /actuator/prometheus.
 *   - {@code jobExecutor} is a plain {@link SpringJobExecutor} wired with
 *     our diagnostic rejected handler. No batch-level wrapping; per-activity
 *     timing comes from {@link ActivityTracker} on DEBUG.
 *
 * Pool/queue sizes are read from camunda.bpm.job-execution.* (same
 * properties the starter would honour).
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
  public ThreadPoolTaskExecutor camundaTaskExecutor() {
    ThreadPoolTaskExecutor te = new ThreadPoolTaskExecutor();
    te.setCorePoolSize(corePoolSize);
    te.setMaxPoolSize(maxPoolSize);
    te.setQueueCapacity(queueCapacity);
    te.setThreadNamePrefix("jobExecutor-");
    te.setRejectedExecutionHandler(new AbortPolicy()); // SpringJobExecutor expects the exception
    te.initialize();
    return te;
  }

  @Bean
  public JobExecutor jobExecutor(ThreadPoolTaskExecutor te) {
    SpringJobExecutor je = new SpringJobExecutor();
    je.setTaskExecutor(te);
    je.setRejectedJobsHandler(new LoggingRejectedJobsHandler(te));
    je.setMaxJobsPerAcquisition(maxJobsPerAcq);
    je.setWaitTimeInMillis(waitMs);
    je.setLockTimeInMillis(lockMs);
    return je;
  }

  // ---------------------------------------------------------------------------

  public static class LoggingRejectedJobsHandler implements RejectedJobsHandler {
    private static final Logger LOG = LoggerFactory.getLogger(LoggingRejectedJobsHandler.class);
    private final ThreadPoolTaskExecutor te;
    private final AtomicLong totalRejected = new AtomicLong();

    public LoggingRejectedJobsHandler(ThreadPoolTaskExecutor te) { this.te = te; }
    public long getTotalRejected() { return totalRejected.get(); }

    @Override
    public void jobsRejected(List<String> jobIds, ProcessEngineImpl engine, JobExecutor je) {
      totalRejected.addAndGet(jobIds.size());
      ThreadPoolExecutor tpe = te.getThreadPoolExecutor();
      LOG.error("""
          JOBEXECUTOR QUEUE OVERFLOW
            jobIds          : {}
            totalRejected   : {}
            pool            : core={} active={} max={}
            queue           : {}/{} (remaining {})
          Currently running (thread -> activity/PI):
          {}""",
          jobIds, totalRejected.get(),
          te.getCorePoolSize(), tpe.getActiveCount(), te.getMaxPoolSize(),
          tpe.getQueue().size(), te.getQueueCapacity(), tpe.getQueue().remainingCapacity(),
          ActivityTracker.formatRunning());

      // Unlock the rejected jobs so the next acquisition cycle picks them up
      // (same behaviour as the default NotifyAcquisitionRejectedJobsHandler).
      var cmdExec = engine.getProcessEngineConfiguration().getCommandExecutorTxRequiresNew();
      for (String id : jobIds) {
        try { cmdExec.execute(new UnlockJobCmd(id)); }
        catch (Exception e) { LOG.warn("could not unlock job {}: {}", id, e.toString()); }
      }
    }
  }
}
