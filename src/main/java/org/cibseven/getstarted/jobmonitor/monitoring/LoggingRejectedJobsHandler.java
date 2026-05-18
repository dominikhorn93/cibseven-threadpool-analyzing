package org.cibseven.getstarted.jobmonitor.monitoring;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.cibseven.bpm.engine.impl.ProcessEngineImpl;
import org.cibseven.bpm.engine.impl.cmd.UnlockJobCmd;
import org.cibseven.bpm.engine.impl.interceptor.CommandExecutor;
import org.cibseven.bpm.engine.impl.jobexecutor.JobExecutor;
import org.cibseven.bpm.engine.impl.jobexecutor.RejectedJobsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Camunda RejectedJobsHandler that on overflow:
 *
 *  1) emits a detailed log describing what happened (which jobIds,
 *     pool/queue state, who is doing what on which thread/activity/PI),
 *  2) "unlocks" the rejected jobs in the engine state so the next
 *     acquisition cycle can pick them up again.
 *
 * Step 2 is the default behaviour of
 * {@code NotifyAcquisitionRejectedJobsHandler}. We do the same but
 * prepend the diagnostic log.
 */
public class LoggingRejectedJobsHandler implements RejectedJobsHandler {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingRejectedJobsHandler.class);

  private final ThreadContextRegistry registry;
  private final InstrumentedTaskExecutor taskExecutor;
  private final AtomicLong rejectedJobsTotal = new AtomicLong();

  public LoggingRejectedJobsHandler(
      ThreadContextRegistry registry,
      InstrumentedTaskExecutor taskExecutor) {
    this.registry = registry;
    this.taskExecutor = taskExecutor;
  }

  public long getRejectedJobsTotal() { return rejectedJobsTotal.get(); }

  @Override
  public void jobsRejected(List<String> jobIds, ProcessEngineImpl processEngine, JobExecutor jobExecutor) {
    rejectedJobsTotal.addAndGet(jobIds.size());

    LOG.error("""
        =========================================================================
        JOBEXECUTOR QUEUE OVERFLOW
          rejected jobIds : {}
          rejected count  : {} (total since start: {})
        TaskExecutor state
          poolSize        : {} (core={}, max={})
          activeThreads   : {}
          queue           : {}/{} (remaining capacity: {})
          highWater queue : {}
          highWater active: {}
          rejected events : {}
        JobExecutor (CIB seven)
          name            : {}
          maxJobsPerAcq   : {}
          waitTimeMs      : {}
        Currently running (thread -> activity/PI):
        {}
        In-flight batches (accepted by the TaskExecutor):
        {}
        =========================================================================
        """,
        jobIds, jobIds.size(), rejectedJobsTotal.get(),
        taskExecutor.getPoolSize(), taskExecutor.getCorePoolSize(), taskExecutor.getMaxPoolSize(),
        taskExecutor.getActiveCount(),
        taskExecutor.getQueueSize(), taskExecutor.getQueueCapacity(), taskExecutor.getQueueRemainingCapacity(),
        taskExecutor.getQueueHighWater(),
        taskExecutor.getActiveHighWater(),
        taskExecutor.getRejectedCount(),
        jobExecutor.getName(),
        jobExecutor.getMaxJobsPerAcquisition(),
        jobExecutor.getWaitTimeInMillis(),
        formatThreads(),
        formatBatches());

    // Engine state: unlock the jobs so the next acquisition cycle can
    // find them again.
    unlockJobs(jobIds, processEngine);
  }

  private void unlockJobs(List<String> jobIds, ProcessEngineImpl processEngine) {
    CommandExecutor cmdExecutor = processEngine.getProcessEngineConfiguration()
        .getCommandExecutorTxRequiresNew();
    for (String jobId : jobIds) {
      try {
        cmdExecutor.execute(new UnlockJobCmd(jobId));
      } catch (Exception e) {
        LOG.warn("Could not unlock job {}: {}", jobId, e.toString());
      }
    }
  }

  private String formatThreads() {
    StringBuilder sb = new StringBuilder();
    for (ThreadJobContext c : registry.liveThreads()) {
      sb.append(String.format("  - %-30s job=%s pi=%s activity=%s ageMs=%d%n",
          c.threadName(), c.jobId(), c.processInstanceId(),
          c.activityId(), c.ageMillis()));
    }
    if (sb.length() == 0) sb.append("  (no thread contexts registered)\n");
    return sb.toString();
  }

  private String formatBatches() {
    StringBuilder sb = new StringBuilder();
    for (JobBatchInfo b : registry.liveBatches()) {
      sb.append(String.format("  - batch=%s state=%s thread=%s waitMs=%d execMs=%d jobs=%s%n",
          b.batchId(), b.state(), b.threadName(), b.waitMillis(), b.execMillis(), b.jobIds()));
    }
    if (sb.length() == 0) sb.append("  (no in-flight batches)\n");
    return sb.toString();
  }
}
