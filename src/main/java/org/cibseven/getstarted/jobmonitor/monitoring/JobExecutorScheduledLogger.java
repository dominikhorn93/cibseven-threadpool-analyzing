package org.cibseven.getstarted.jobmonitor.monitoring;

import org.cibseven.bpm.engine.ManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes a snapshot block to the log at the configured interval.
 *
 * Why: when something feels off you do not have to wire up metrics
 * dashboards first — the log already shows a periodic picture. Turn
 * down in production (jobmonitor.snapshot-log-interval-seconds=0 or
 * lift the log level of this class to WARN).
 */
@Component
public class JobExecutorScheduledLogger {

  private static final Logger LOG = LoggerFactory.getLogger(JobExecutorScheduledLogger.class);

  private final InstrumentedTaskExecutor taskExecutor;
  private final ThreadContextRegistry registry;
  private final LoggingRejectedJobsHandler rejectedHandler;
  private final ManagementService managementService;

  public JobExecutorScheduledLogger(
      InstrumentedTaskExecutor taskExecutor,
      ThreadContextRegistry registry,
      LoggingRejectedJobsHandler rejectedHandler,
      ManagementService managementService) {
    this.taskExecutor = taskExecutor;
    this.registry = registry;
    this.rejectedHandler = rejectedHandler;
    this.managementService = managementService;
  }

  @Scheduled(
      fixedDelayString = "${jobmonitor.snapshot-log-interval-seconds:5}",
      timeUnit = java.util.concurrent.TimeUnit.SECONDS,
      initialDelayString = "5")
  public void snapshot() {
    long pending = safeCount();
    long due = safeDueCount();

    StringBuilder threads = new StringBuilder();
    registry.liveThreads().forEach(c -> threads.append(String.format(
        "    %-30s job=%s pi=%s activity=%s ageMs=%d%n",
        c.threadName(), shorten(c.jobId()), shorten(c.processInstanceId()),
        c.activityId(), c.ageMillis())));
    if (threads.length() == 0) threads.append("    (idle)\n");

    StringBuilder batches = new StringBuilder();
    registry.liveBatches().forEach(b -> batches.append(String.format(
        "    batch=%s state=%-7s thread=%-25s waitMs=%-5d execMs=%-5d jobs=%s%n",
        b.batchId(), b.state(), String.valueOf(b.threadName()),
        b.waitMillis(), b.execMillis(), b.jobIds())));
    if (batches.length() == 0) batches.append("    (none)\n");

    LOG.info("""

        --- JobExecutor Snapshot --------------------------------------------------
        pool   active={}/{}/{}    queue={}/{} (hwm queue={}, hwm active={})
        events submitted={} completed={} rejectedEvents={} rejectedJobs={}
        engine jobsPending(DB)={} jobsDue(DB)={}
        Live activities:
        {}        Live batches:
        {}---------------------------------------------------------------------------""",
        taskExecutor.getActiveCount(), taskExecutor.getPoolSize(), taskExecutor.getMaxPoolSize(),
        taskExecutor.getQueueSize(), taskExecutor.getQueueCapacity(),
        taskExecutor.getQueueHighWater(), taskExecutor.getActiveHighWater(),
        taskExecutor.getSubmittedCount(), taskExecutor.getCompletedCount(),
        taskExecutor.getRejectedCount(), rejectedHandler.getRejectedJobsTotal(),
        pending, due,
        threads, batches);
  }

  private long safeCount() {
    try { return managementService.createJobQuery().count(); } catch (Exception e) { return -1; }
  }
  private long safeDueCount() {
    try { return managementService.createJobQuery().executable().count(); } catch (Exception e) { return -1; }
  }
  private static String shorten(String s) {
    return s == null ? "-" : (s.length() > 12 ? s.substring(0, 8) + "…" : s);
  }
}
