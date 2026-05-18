package org.cibseven.getstarted.jobmonitor.monitoring;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Metadata about a job batch as the SpringJobExecutor hands it to the
 * TaskExecutor (up to max-jobs-per-acquisition jobs per batch).
 *
 * Every batch gets its own ID so you can trace it through
 * acquisition → queue → pickup → execution → done in the logs.
 */
public record JobBatchInfo(
    String batchId,
    List<String> jobIds,
    Instant submittedAt,
    Instant startedAt,
    Instant finishedAt,
    String threadName,
    State state,
    String failureReason) {

  public enum State { QUEUED, RUNNING, DONE, FAILED, REJECTED }

  public static JobBatchInfo queued(List<String> jobIds) {
    return new JobBatchInfo(
        UUID.randomUUID().toString().substring(0, 8),
        List.copyOf(jobIds),
        Instant.now(),
        null,
        null,
        null,
        State.QUEUED,
        null);
  }

  public JobBatchInfo started(String threadName) {
    return new JobBatchInfo(batchId, jobIds, submittedAt, Instant.now(),
        null, threadName, State.RUNNING, null);
  }

  public JobBatchInfo finished() {
    return new JobBatchInfo(batchId, jobIds, submittedAt, startedAt,
        Instant.now(), threadName, State.DONE, null);
  }

  public JobBatchInfo failed(Throwable t) {
    return new JobBatchInfo(batchId, jobIds, submittedAt, startedAt,
        Instant.now(), threadName, State.FAILED, t.toString());
  }

  public JobBatchInfo rejected(String reason) {
    return new JobBatchInfo(batchId, jobIds, submittedAt, null, null,
        null, State.REJECTED, reason);
  }

  public long waitMillis() {
    if (startedAt == null) return submittedAt == null ? 0 :
        System.currentTimeMillis() - submittedAt.toEpochMilli();
    return startedAt.toEpochMilli() - submittedAt.toEpochMilli();
  }

  public long execMillis() {
    if (startedAt == null) return 0;
    Instant end = finishedAt != null ? finishedAt : Instant.now();
    return end.toEpochMilli() - startedAt.toEpochMilli();
  }
}
