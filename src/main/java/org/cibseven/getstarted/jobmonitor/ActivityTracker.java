package org.cibseven.getstarted.jobmonitor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.jobexecutor.JobExecutorContext;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;
import org.cibseven.bpm.spring.boot.starter.event.ExecutionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Hooks into every BPMN activity start/end via the {@link ExecutionEvent}
 * the cibseven Spring Boot starter publishes (its {@code EventPublisherPlugin}
 * is auto-registered, defaults are all on).
 *
 * Per activity boundary we:
 *   - log {@code ACTIVITY START/END} with job, pi, activity, thread, durationMs,
 *   - update SLF4J MDC so any delegate log line gets the context for free,
 *   - keep a static "thread name -> {job, pi, activity, startedAt}" map that
 *     {@link JobMonitoring.LoggingRejectedJobsHandler} reads when it prints
 *     the overflow diagnostic block.
 *
 * The static map is a pragmatic shortcut — fine for a sample. In a larger
 * codebase you'd inject this @Component into the handler instead.
 */
@Component
public class ActivityTracker {

  private static final Logger LOG = LoggerFactory.getLogger(ActivityTracker.class);
  private static final Map<String, String> CURRENT = new ConcurrentHashMap<>();

  @EventListener
  public void onActivity(ExecutionEvent e) {
    String thread = Thread.currentThread().getName();
    String event = e.getEventName();

    if ("start".equals(event)) {
      JobExecutorContext jec = Context.getJobExecutorContext();
      JobEntity job = jec == null ? null : jec.getCurrentJob();
      String jobId = job == null ? null : job.getId();
      String pi = e.getProcessInstanceId();
      String act = e.getCurrentActivityId();
      CURRENT.put(thread, String.format("job=%s pi=%s activity=%s startedAt=%d",
          jobId, pi, act, System.currentTimeMillis()));
      if (jobId != null) MDC.put("jobId", jobId);
      MDC.put("processInstanceId", pi);
      MDC.put("activityId", act);
      LOG.info("ACTIVITY START  job={} pi={} activity={} thread={}", jobId, pi, act, thread);

    } else if ("end".equals(event)) {
      String prev = CURRENT.remove(thread);
      long startedAt = parseStartedAt(prev);
      LOG.info("ACTIVITY END    pi={} activity={} thread={} durationMs={}",
          e.getProcessInstanceId(), e.getCurrentActivityId(), thread,
          System.currentTimeMillis() - startedAt);
      MDC.remove("jobId");
      MDC.remove("processInstanceId");
      MDC.remove("activityId");
    }
  }

  /** Rendered into the JOBEXECUTOR QUEUE OVERFLOW log block. */
  public static String currentlyRunning() {
    if (CURRENT.isEmpty()) return "  (idle)";
    StringBuilder sb = new StringBuilder();
    long now = System.currentTimeMillis();
    CURRENT.forEach((thread, ctx) -> {
      long started = parseStartedAt(ctx);
      int cut = ctx.lastIndexOf("startedAt=");
      sb.append("  - ").append(thread).append("  ")
        .append(cut < 0 ? ctx : ctx.substring(0, cut))
        .append("ageMs=").append(now - started).append('\n');
    });
    return sb.toString();
  }

  private static long parseStartedAt(String ctx) {
    if (ctx == null) return System.currentTimeMillis();
    int i = ctx.lastIndexOf("startedAt=");
    if (i < 0) return System.currentTimeMillis();
    try { return Long.parseLong(ctx.substring(i + "startedAt=".length())); }
    catch (NumberFormatException e) { return System.currentTimeMillis(); }
  }
}
