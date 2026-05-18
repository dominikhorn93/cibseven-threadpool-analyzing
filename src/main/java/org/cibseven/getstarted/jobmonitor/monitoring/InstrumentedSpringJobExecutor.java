package org.cibseven.getstarted.jobmonitor.monitoring;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.cibseven.bpm.engine.impl.ProcessEngineImpl;
import org.cibseven.bpm.engine.spring.components.jobexecutor.SpringJobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Custom SpringJobExecutor that instruments every job batch (a list of
 * jobIds the acquisition thread hands to the TaskExecutor).
 *
 * Before super.executeJobs():
 *   - record JobBatchInfo (UUID, jobIds, submittedAt) as QUEUED in the
 *     registry
 *   - emit a "BATCH SUBMITTED" log line with pool/queue state
 *
 * Inside the wrapped runnable (i.e. on the worker thread):
 *   - flip registry state to RUNNING with the threadName
 *   - set MDC "batchId" so every log line produced by the job is
 *     correlatable
 *   - call super.run() (= the real ExecuteJobsRunnable that runs the
 *     jobs)
 *   - on success: registry → DONE, push to history; on exception:
 *     → FAILED, push to history
 *
 * If taskExecutor.execute() throws RejectedExecutionException the
 * default SpringJobExecutor would know the queue is full and call the
 * Camunda RejectedJobsHandler. We replicate exactly that semantics
 * plus an extra log line and a history entry.
 */
public class InstrumentedSpringJobExecutor extends SpringJobExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(InstrumentedSpringJobExecutor.class);

  private final ThreadContextRegistry registry;

  public InstrumentedSpringJobExecutor(ThreadContextRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void executeJobs(List<String> jobIds, ProcessEngineImpl processEngine) {
    JobBatchInfo info = JobBatchInfo.queued(jobIds);
    registry.onBatchSubmitted(info);

    Runnable original = getExecuteJobsRunnable(jobIds, processEngine);
    Runnable wrapped = () -> {
      String previousBatch = MDC.get("batchId");
      MDC.put("batchId", info.batchId());
      registry.onBatchStarted(info.batchId(), Thread.currentThread().getName());
      long t0 = System.currentTimeMillis();
      LOG.info("BATCH START     batchId={} jobs={} thread={} waitMs={}",
          info.batchId(), jobIds.size(), Thread.currentThread().getName(),
          System.currentTimeMillis() - info.submittedAt().toEpochMilli());
      try {
        original.run();
        registry.onBatchFinished(info.batchId());
        LOG.info("BATCH DONE      batchId={} jobs={} thread={} execMs={}",
            info.batchId(), jobIds.size(), Thread.currentThread().getName(),
            System.currentTimeMillis() - t0);
      } catch (Throwable t) {
        registry.onBatchFailed(info.batchId(), t);
        LOG.error("BATCH FAILED    batchId={} jobs={} thread={} execMs={} cause={}",
            info.batchId(), jobIds.size(), Thread.currentThread().getName(),
            System.currentTimeMillis() - t0, t.toString(), t);
        throw t;
      } finally {
        if (previousBatch == null) MDC.remove("batchId");
        else MDC.put("batchId", previousBatch);
      }
    };

    try {
      LOG.info("BATCH SUBMITTED batchId={} jobs={} jobIds={}",
          info.batchId(), jobIds.size(), jobIds);
      getTaskExecutor().execute(wrapped);
    } catch (RejectedExecutionException rex) {
      // Queue full and all threads busy → Spring/JDK rejects.
      // We log the full state and then delegate to the configured
      // Camunda RejectedJobsHandler (which resets the job locks so the
      // jobs can be re-acquired).
      registry.onBatchRejected(info, rex.toString());
      logRejectedExecution(processEngine, jobIds.size());
      LOG.error("BATCH REJECTED  batchId={} jobs={} reason={}",
          info.batchId(), jobIds, rex.toString());
      getRejectedJobsHandler().jobsRejected(jobIds, processEngine, this);
    }
  }
}
