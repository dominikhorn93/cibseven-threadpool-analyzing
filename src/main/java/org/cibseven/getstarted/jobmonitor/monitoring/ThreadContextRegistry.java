package org.cibseven.getstarted.jobmonitor.monitoring;

import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Central view of "what is running right now".
 *
 *  - liveByThread:   thread name → what that thread is currently
 *                    executing (populated by JobMonitorExecutionListener).
 *  - liveBatches:    batchId → state of a job batch that was handed to
 *                    the TaskExecutor (populated by
 *                    InstrumentedSpringJobExecutor).
 *  - recentHistory:  ring buffer of completed / rejected batches so
 *                    you can still see what happened after an incident.
 */
@Component
public class ThreadContextRegistry {

  private final Map<String, ThreadJobContext> liveByThread = new ConcurrentHashMap<>();
  private final Map<String, JobBatchInfo> liveBatches = new ConcurrentHashMap<>();
  private final Deque<JobBatchInfo> recentHistory = new ConcurrentLinkedDeque<>();

  private final int historySize;

  public ThreadContextRegistry(
      @Value("${jobmonitor.history-ring-size:200}") int historySize) {
    this.historySize = historySize;
  }

  public void onActivityStart(ThreadJobContext ctx) {
    liveByThread.put(ctx.threadName(), ctx);
  }

  public void onActivityEnd(String threadName) {
    liveByThread.remove(threadName);
  }

  public void onBatchSubmitted(JobBatchInfo info) {
    liveBatches.put(info.batchId(), info);
  }

  public void onBatchStarted(String batchId, String threadName) {
    liveBatches.computeIfPresent(batchId, (k, v) -> v.started(threadName));
  }

  public void onBatchFinished(String batchId) {
    JobBatchInfo done = liveBatches.remove(batchId);
    if (done != null) pushHistory(done.finished());
  }

  public void onBatchFailed(String batchId, Throwable t) {
    JobBatchInfo failed = liveBatches.remove(batchId);
    if (failed != null) pushHistory(failed.failed(t));
  }

  public void onBatchRejected(JobBatchInfo info, String reason) {
    liveBatches.remove(info.batchId());
    pushHistory(info.rejected(reason));
  }

  private void pushHistory(JobBatchInfo info) {
    recentHistory.addFirst(info);
    while (recentHistory.size() > historySize) recentHistory.pollLast();
  }

  public Collection<ThreadJobContext> liveThreads() {
    return Collections.unmodifiableCollection(new LinkedHashMap<>(liveByThread).values());
  }

  public Collection<JobBatchInfo> liveBatches() {
    return Collections.unmodifiableCollection(new LinkedHashMap<>(liveBatches).values());
  }

  public Collection<JobBatchInfo> recentHistory() {
    return Collections.unmodifiableCollection(new LinkedHashMap<Integer, JobBatchInfo>() {{
      int i = 0; for (JobBatchInfo b : recentHistory) put(i++, b);
    }}.values());
  }

  public int liveThreadCount() { return liveByThread.size(); }
  public int liveBatchCount()  { return liveBatches.size(); }
}
