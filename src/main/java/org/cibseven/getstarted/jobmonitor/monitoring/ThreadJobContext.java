package org.cibseven.getstarted.jobmonitor.monitoring;

import java.time.Instant;

/**
 * Snapshot of what a job-executor thread is currently doing.
 * Populated by JobMonitorExecutionListener on activity start and
 * cleared on activity end. Multiple activities can run inside the
 * same transaction, in which case the context gets overwritten.
 */
public record ThreadJobContext(
    String threadName,
    String jobId,
    String jobType,
    String processInstanceId,
    String processDefinitionKey,
    String activityId,
    String activityName,
    String businessKey,
    String batchId,
    Instant startedAt) {

  public long ageMillis() {
    return startedAt == null ? 0 : (System.currentTimeMillis() - startedAt.toEpochMilli());
  }
}
