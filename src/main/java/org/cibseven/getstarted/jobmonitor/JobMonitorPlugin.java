package org.cibseven.getstarted.jobmonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.jobexecutor.JobExecutorContext;
import org.cibseven.bpm.engine.impl.persistence.entity.JobEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.util.xml.Element;
import org.cibseven.bpm.spring.boot.starter.util.SpringBootProcessEnginePlugin;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * ProcessEnginePlugin that attaches an ExecutionListener to every parsed
 * BPMN activity. The listener:
 *
 *   - populates SLF4J MDC (jobId, processInstanceId, activityId) so the
 *     logback pattern echoes the context in every line a delegate logs,
 *   - keeps a static "thread name -> {job, pi, activity}" map so the
 *     LoggingRejectedJobsHandler can print "currently running" on overflow.
 *
 * The static map is a pragmatic shortcut — fine for a demo / sample. In
 * a larger codebase you would inject a @Component registry instead.
 */
@Component
public class JobMonitorPlugin extends SpringBootProcessEnginePlugin {

  private static final Map<String, String> CURRENT = new ConcurrentHashMap<>();

  @Override
  public void preInit(ProcessEngineConfigurationImpl cfg) {
    List<BpmnParseListener> listeners = cfg.getCustomPreBPMNParseListeners();
    if (listeners == null) cfg.setCustomPreBPMNParseListeners(listeners = new ArrayList<>());
    listeners.add(new AttachAllActivities(new TrackingListener()));
  }

  /** Rendered into the JOBEXECUTOR QUEUE OVERFLOW log block. */
  public static String currentlyRunning() {
    if (CURRENT.isEmpty()) return "  (idle)";
    StringBuilder sb = new StringBuilder();
    long now = System.currentTimeMillis();
    CURRENT.forEach((thread, ctx) -> {
      // ctx looks like "job=… pi=… activity=… startedAt=<epoch>"
      int sep = ctx.lastIndexOf("startedAt=");
      long age = sep < 0 ? -1 : now - Long.parseLong(ctx.substring(sep + "startedAt=".length()));
      sb.append("  - ").append(thread).append("  ")
        .append(sep < 0 ? ctx : ctx.substring(0, sep))
        .append("ageMs=").append(age).append('\n');
    });
    return sb.toString();
  }

  /** Attaches the listener to every parsed activity (start + end). */
  static class AttachAllActivities extends AbstractBpmnParseListener {
    private final ExecutionListener l;
    AttachAllActivities(ExecutionListener l) { this.l = l; }
    private void on(ActivityImpl a) {
      a.addListener(ExecutionListener.EVENTNAME_START, l);
      a.addListener(ExecutionListener.EVENTNAME_END,   l);
    }
    @Override public void parseServiceTask(Element s, ScopeImpl sc, ActivityImpl a)             { on(a); }
    @Override public void parseUserTask(Element s, ScopeImpl sc, ActivityImpl a)                { on(a); }
    @Override public void parseScriptTask(Element s, ScopeImpl sc, ActivityImpl a)              { on(a); }
    @Override public void parseBusinessRuleTask(Element s, ScopeImpl sc, ActivityImpl a)        { on(a); }
    @Override public void parseTask(Element s, ScopeImpl sc, ActivityImpl a)                    { on(a); }
    @Override public void parseSendTask(Element s, ScopeImpl sc, ActivityImpl a)                { on(a); }
    @Override public void parseReceiveTask(Element s, ScopeImpl sc, ActivityImpl a)             { on(a); }
    @Override public void parseCallActivity(Element s, ScopeImpl sc, ActivityImpl a)            { on(a); }
    @Override public void parseStartEvent(Element s, ScopeImpl sc, ActivityImpl a)              { on(a); }
    @Override public void parseEndEvent(Element s, ScopeImpl sc, ActivityImpl a)                { on(a); }
    @Override public void parseExclusiveGateway(Element s, ScopeImpl sc, ActivityImpl a)        { on(a); }
    @Override public void parseParallelGateway(Element s, ScopeImpl sc, ActivityImpl a)         { on(a); }
    @Override public void parseInclusiveGateway(Element s, ScopeImpl sc, ActivityImpl a)        { on(a); }
    @Override public void parseIntermediateCatchEvent(Element s, ScopeImpl sc, ActivityImpl a)  { on(a); }
    @Override public void parseIntermediateThrowEvent(Element s, ScopeImpl sc, ActivityImpl a)  { on(a); }
    @Override public void parseBoundaryEvent(Element s, ScopeImpl sc, ActivityImpl a)           { on(a); }
    @Override public void parseSubProcess(Element s, ScopeImpl sc, ActivityImpl a)              { on(a); }
  }

  /** Fired by every activity start/end. Updates MDC and the static map. */
  static class TrackingListener implements ExecutionListener {
    @Override public void notify(DelegateExecution e) {
      String thread = Thread.currentThread().getName();
      if (EVENTNAME_START.equals(e.getEventName())) {
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
      } else if (EVENTNAME_END.equals(e.getEventName())) {
        CURRENT.remove(thread);
        MDC.remove("jobId");
        MDC.remove("processInstanceId");
        MDC.remove("activityId");
      }
    }
  }
}
