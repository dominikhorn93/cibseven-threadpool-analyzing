package org.cibseven.getstarted.jobmonitor.monitoring;

import java.time.Instant;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.jobexecutor.JobExecutorContext;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Attached by {@link JobMonitorBpmnParseListener} to every BPMN
 * activity, fired on start and end. Records:
 *
 *  - which thread is currently running this activity
 *  - the matching process-instance and job IDs
 *  - the activity duration (delta between start and end)
 *
 * Also populates the SLF4J MDC so the Logback pattern can echo the
 * context in every log line that originates from delegate code.
 */
public class JobMonitorExecutionListener implements ExecutionListener {

  private static final Logger LOG = LoggerFactory.getLogger(JobMonitorExecutionListener.class);

  static final String MDC_JOB_ID = "jobId";
  static final String MDC_PROCESS_INSTANCE_ID = "processInstanceId";
  static final String MDC_PROCESS_DEFINITION_KEY = "processDefinitionKey";
  static final String MDC_ACTIVITY_ID = "activityId";
  static final String MDC_BUSINESS_KEY = "businessKey";

  private final ThreadContextRegistry registry;
  private final boolean logLifecycle;

  public JobMonitorExecutionListener(ThreadContextRegistry registry, boolean logLifecycle) {
    this.registry = registry;
    this.logLifecycle = logLifecycle;
  }

  @Override
  public void notify(DelegateExecution execution) {
    String event = execution.getEventName();
    if (EVENTNAME_START.equals(event)) {
      onStart(execution);
    } else if (EVENTNAME_END.equals(event)) {
      onEnd(execution);
    }
  }

  private void onStart(DelegateExecution execution) {
    JobEntity currentJob = currentJob();
    String jobId = currentJob != null ? currentJob.getId() : null;
    String jobType = currentJob != null ? currentJob.getJobHandlerType() : "no-job(sync)";
    String pdKey = execution.getProcessDefinitionId() == null
        ? null
        : execution.getProcessDefinitionId().split(":")[0];

    ThreadJobContext ctx = new ThreadJobContext(
        Thread.currentThread().getName(),
        jobId,
        jobType,
        execution.getProcessInstanceId(),
        pdKey,
        execution.getCurrentActivityId(),
        execution.getCurrentActivityName(),
        execution.getBusinessKey(),
        null,
        Instant.now());

    registry.onActivityStart(ctx);
    if (jobId != null) MDC.put(MDC_JOB_ID, jobId);
    MDC.put(MDC_PROCESS_INSTANCE_ID, execution.getProcessInstanceId());
    if (pdKey != null) MDC.put(MDC_PROCESS_DEFINITION_KEY, pdKey);
    MDC.put(MDC_ACTIVITY_ID, execution.getCurrentActivityId());
    if (execution.getBusinessKey() != null) MDC.put(MDC_BUSINESS_KEY, execution.getBusinessKey());

    if (logLifecycle) {
      LOG.info("ACTIVITY START  job={} jobType={} activity={} pi={} thread={}",
          jobId, jobType, execution.getCurrentActivityId(),
          execution.getProcessInstanceId(), Thread.currentThread().getName());
    }
  }

  private void onEnd(DelegateExecution execution) {
    String thread = Thread.currentThread().getName();
    ThreadJobContext before = null;
    for (ThreadJobContext c : registry.liveThreads()) {
      if (c.threadName().equals(thread)) { before = c; break; }
    }
    long ms = before == null ? -1 : before.ageMillis();
    if (logLifecycle) {
      LOG.info("ACTIVITY END    activity={} pi={} thread={} durationMs={}",
          execution.getCurrentActivityId(), execution.getProcessInstanceId(), thread, ms);
    }
    registry.onActivityEnd(thread);
    MDC.remove(MDC_JOB_ID);
    MDC.remove(MDC_PROCESS_INSTANCE_ID);
    MDC.remove(MDC_PROCESS_DEFINITION_KEY);
    MDC.remove(MDC_ACTIVITY_ID);
    MDC.remove(MDC_BUSINESS_KEY);
  }

  private JobEntity currentJob() {
    JobExecutorContext jec = Context.getJobExecutorContext();
    return jec == null ? null : jec.getCurrentJob();
  }
}
