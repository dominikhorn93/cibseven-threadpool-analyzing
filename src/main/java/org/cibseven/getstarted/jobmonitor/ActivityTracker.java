package org.cibseven.getstarted.jobmonitor;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Behaviour on the {@code org.cibseven.getstarted.jobmonitor} logger:
 *   DEBUG — per-activity {@code ACTIVITY START/END} plus the full
 *           "Currently running" snapshot after each event.
 *   INFO  — silent. The overflow diagnostic (LOG.error in
 *           {@link JobMonitoring.LoggingRejectedJobsHandler}) still fires.
 *
 * The internal {@link Running} records back both the log output and
 * {@link JobExecutorEndpoint} (/actuator/jobexecutor).
 */
@Component
public class ActivityTracker {

  private static final Logger LOG = LoggerFactory.getLogger(ActivityTracker.class);
  private static final Map<String, Running> CURRENT = new ConcurrentHashMap<>();

  public record Running(String thread, String jobId, String processInstanceId,
                        String activity, long startedAtMs) {
    public long ageMs() { return System.currentTimeMillis() - startedAtMs; }
  }

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
      CURRENT.put(thread, new Running(thread, jobId, pi, act, System.currentTimeMillis()));
      if (jobId != null) MDC.put("jobId", jobId);
      MDC.put("processInstanceId", pi);
      MDC.put("activityId", act);
      if (LOG.isDebugEnabled()) {
        LOG.debug("ACTIVITY START  job={} pi={} activity={} thread={}", jobId, pi, act, thread);
        LOG.debug("Currently running ({}):\n{}", CURRENT.size(), formatRunning());
      }

    } else if ("end".equals(event)) {
      Running prev = CURRENT.remove(thread);
      long dur = prev == null ? 0 : prev.ageMs();
      if (LOG.isDebugEnabled()) {
        LOG.debug("ACTIVITY END    pi={} activity={} thread={} durationMs={}",
            e.getProcessInstanceId(), e.getCurrentActivityId(), thread, dur);
        LOG.debug("Currently running ({}):\n{}", CURRENT.size(), formatRunning());
      }
      MDC.remove("jobId");
      MDC.remove("processInstanceId");
      MDC.remove("activityId");
    }
  }

  /** Rendered into the overflow block (multi-line string). */
  public static String formatRunning() {
    if (CURRENT.isEmpty()) return "  (idle)";
    StringBuilder sb = new StringBuilder();
    CURRENT.values().forEach(r -> sb.append(String.format(
        "  - %s  job=%s pi=%s activity=%s ageMs=%d%n",
        r.thread(), r.jobId(), r.processInstanceId(), r.activity(), r.ageMs())));
    return sb.toString();
  }

  /** JSON-friendly snapshot used by {@link JobExecutorEndpoint}. */
  public static List<Map<String, Object>> running() {
    long now = System.currentTimeMillis();
    return CURRENT.values().stream().<Map<String, Object>>map(r -> {
      var m = new LinkedHashMap<String, Object>();
      m.put("thread", r.thread());
      m.put("jobId", r.jobId());
      m.put("processInstanceId", r.processInstanceId());
      m.put("activity", r.activity());
      m.put("startedAt", r.startedAtMs());
      m.put("ageMs", now - r.startedAtMs());
      return m;
    }).toList();
  }

  public static Collection<Running> raw() { return CURRENT.values(); }
}
