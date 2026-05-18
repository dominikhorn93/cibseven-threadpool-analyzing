package org.cibseven.getstarted.jobmonitor.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.impl.jobexecutor.JobExecutor;
import org.springframework.stereotype.Component;

/**
 * Registers gauges/counters for every relevant JobExecutor knob on the
 * Micrometer registry. Exposed via /actuator/prometheus.
 *
 * Tag convention: "executor" = "spring-job-executor"
 *
 * Metrics:
 *   cibseven_jobexecutor_pool_active               (gauge)
 *   cibseven_jobexecutor_pool_size                 (gauge)
 *   cibseven_jobexecutor_pool_core                 (gauge)
 *   cibseven_jobexecutor_pool_max                  (gauge)
 *   cibseven_jobexecutor_queue_size                (gauge)
 *   cibseven_jobexecutor_queue_capacity            (gauge)
 *   cibseven_jobexecutor_queue_remaining           (gauge)
 *   cibseven_jobexecutor_queue_highwater           (gauge)
 *   cibseven_jobexecutor_active_highwater          (gauge)
 *   cibseven_jobexecutor_submitted                 (gauge, total submits)
 *   cibseven_jobexecutor_completed                 (gauge, from JDK pool)
 *   cibseven_jobexecutor_rejected_events           (gauge)
 *   cibseven_jobexecutor_rejected_jobs             (gauge, total rejected jobs)
 *   cibseven_jobexecutor_live_threads              (gauge, active ExecutionListener contexts)
 *   cibseven_jobexecutor_live_batches              (gauge, batches accepted by TaskExecutor but not yet done)
 *   cibseven_jobexecutor_acquisition_waitMs        (gauge, wait time of the JobExecutor)
 *   cibseven_jobexecutor_acquisition_batchSize     (gauge, maxJobsPerAcquisition)
 *   cibseven_engine_jobs_pending                   (gauge, jobs in DB waiting for execution)
 *   cibseven_engine_jobs_due                       (gauge, jobs that are currently due)
 */
@Component
public class JobExecutorMetricsBinder implements MeterBinder {

  private final InstrumentedTaskExecutor taskExecutor;
  private final JobExecutor jobExecutor;
  private final LoggingRejectedJobsHandler rejectedHandler;
  private final ThreadContextRegistry registry;
  private final ManagementService managementService;

  public JobExecutorMetricsBinder(
      InstrumentedTaskExecutor taskExecutor,
      JobExecutor jobExecutor,
      LoggingRejectedJobsHandler rejectedHandler,
      ThreadContextRegistry registry,
      ManagementService managementService) {
    this.taskExecutor = taskExecutor;
    this.jobExecutor = jobExecutor;
    this.rejectedHandler = rejectedHandler;
    this.registry = registry;
    this.managementService = managementService;
  }

  @Override
  public void bindTo(MeterRegistry r) {
    String tagKey = "executor";
    String tagVal = "spring-job-executor";

    r.gauge("cibseven.jobexecutor.pool.active",     tags(tagKey, tagVal), taskExecutor, InstrumentedTaskExecutor::getActiveCount);
    r.gauge("cibseven.jobexecutor.pool.size",       tags(tagKey, tagVal), taskExecutor, InstrumentedTaskExecutor::getPoolSize);
    r.gauge("cibseven.jobexecutor.pool.core",       tags(tagKey, tagVal), taskExecutor, t -> t.getCorePoolSize());
    r.gauge("cibseven.jobexecutor.pool.max",        tags(tagKey, tagVal), taskExecutor, t -> t.getMaxPoolSize());

    r.gauge("cibseven.jobexecutor.queue.size",      tags(tagKey, tagVal), taskExecutor, InstrumentedTaskExecutor::getQueueSize);
    r.gauge("cibseven.jobexecutor.queue.capacity",  tags(tagKey, tagVal), taskExecutor, t -> t.getQueueCapacity());
    r.gauge("cibseven.jobexecutor.queue.remaining", tags(tagKey, tagVal), taskExecutor, InstrumentedTaskExecutor::getQueueRemainingCapacity);
    r.gauge("cibseven.jobexecutor.queue.highwater", tags(tagKey, tagVal), taskExecutor, InstrumentedTaskExecutor::getQueueHighWater);
    r.gauge("cibseven.jobexecutor.active.highwater",tags(tagKey, tagVal), taskExecutor, InstrumentedTaskExecutor::getActiveHighWater);

    r.gauge("cibseven.jobexecutor.submitted",       tags(tagKey, tagVal), taskExecutor, t -> (double) t.getSubmittedCount());
    r.gauge("cibseven.jobexecutor.completed",       tags(tagKey, tagVal), taskExecutor, t -> (double) t.getCompletedCount());
    r.gauge("cibseven.jobexecutor.rejected.events", tags(tagKey, tagVal), taskExecutor, t -> (double) t.getRejectedCount());
    r.gauge("cibseven.jobexecutor.rejected.jobs",   tags(tagKey, tagVal), rejectedHandler, h -> (double) h.getRejectedJobsTotal());

    r.gauge("cibseven.jobexecutor.live.threads",    tags(tagKey, tagVal), registry, ThreadContextRegistry::liveThreadCount);
    r.gauge("cibseven.jobexecutor.live.batches",    tags(tagKey, tagVal), registry, ThreadContextRegistry::liveBatchCount);

    r.gauge("cibseven.jobexecutor.acquisition.batchSize", tags(tagKey, tagVal), jobExecutor, j -> (double) j.getMaxJobsPerAcquisition());
    r.gauge("cibseven.jobexecutor.acquisition.waitMs",    tags(tagKey, tagVal), jobExecutor, j -> (double) j.getWaitTimeInMillis());

    r.gauge("cibseven.engine.jobs.pending", this, JobExecutorMetricsBinder::pendingJobs);
    r.gauge("cibseven.engine.jobs.due",     this, JobExecutorMetricsBinder::dueJobs);
  }

  private double pendingJobs() {
    try {
      return managementService.createJobQuery().count();
    } catch (Exception e) { return Double.NaN; }
  }

  private double dueJobs() {
    try {
      return managementService.createJobQuery().executable().count();
    } catch (Exception e) { return Double.NaN; }
  }

  private static Iterable<io.micrometer.core.instrument.Tag> tags(String k, String v) {
    return io.micrometer.core.instrument.Tags.of(k, v);
  }
}
