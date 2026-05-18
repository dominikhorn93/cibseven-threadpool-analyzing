package org.cibseven.getstarted.jobmonitor.monitoring;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * ThreadPoolTaskExecutor with additional observability:
 *
 *  - high-water marks for queue size and active thread count
 *    (so even retrospectively you can tell how close to the limit you
 *    were).
 *  - counter for rejected executions (JDK-level — i.e. when the pool
 *    queue is full AND no thread is available).
 *  - direct accessors for the internal BlockingQueue and the raw
 *    ThreadPoolExecutor so metrics and REST endpoints can read
 *    everything.
 *
 * The JDK-level rejection handler is the default AbortPolicy (throw
 * RejectedExecutionException), but we also count and log the event.
 * SpringJobExecutor catches the exception afterwards and dispatches
 * to our Camunda RejectedJobsHandler.
 */
public class InstrumentedTaskExecutor extends ThreadPoolTaskExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(InstrumentedTaskExecutor.class);

  private final AtomicInteger queueHighWater = new AtomicInteger();
  private final AtomicInteger activeHighWater = new AtomicInteger();
  private final AtomicLong rejectedCount = new AtomicLong();
  private final AtomicLong submittedCount = new AtomicLong();

  @Override
  public void execute(Runnable task) {
    submittedCount.incrementAndGet();
    updateHighWaters();
    try {
      super.execute(task);
    } catch (RuntimeException rex) {
      rejectedCount.incrementAndGet();
      LOG.warn("TaskExecutor REJECTED a runnable. pool={}/{} queue={}/{} rejectedTotal={}",
          getActiveCount(), getMaxPoolSize(),
          getQueueSize(), getQueueCapacity(),
          rejectedCount.get());
      throw rex;
    }
  }

  private void updateHighWaters() {
    int q = getQueueSize();
    queueHighWater.accumulateAndGet(q, Math::max);
    int a = getActiveCount();
    activeHighWater.accumulateAndGet(a, Math::max);
  }

  public int getQueueSize() {
    BlockingQueue<Runnable> q = queue();
    return q == null ? 0 : q.size();
  }

  public int getQueueRemainingCapacity() {
    BlockingQueue<Runnable> q = queue();
    return q == null ? 0 : q.remainingCapacity();
  }

  public int getQueueHighWater()    { return queueHighWater.get(); }
  public int getActiveHighWater()   { return activeHighWater.get(); }
  public long getRejectedCount()    { return rejectedCount.get(); }
  public long getSubmittedCount()   { return submittedCount.get(); }

  public long getCompletedCount() {
    ThreadPoolExecutor tpe = getThreadPoolExecutor();
    return tpe == null ? 0 : tpe.getCompletedTaskCount();
  }

  public int getActiveCount() {
    ThreadPoolExecutor tpe = getThreadPoolExecutor();
    return tpe == null ? 0 : tpe.getActiveCount();
  }

  public int getPoolSize() {
    ThreadPoolExecutor tpe = getThreadPoolExecutor();
    return tpe == null ? 0 : tpe.getPoolSize();
  }

  private BlockingQueue<Runnable> queue() {
    ThreadPoolExecutor tpe = getThreadPoolExecutor();
    return tpe == null ? null : tpe.getQueue();
  }
}
