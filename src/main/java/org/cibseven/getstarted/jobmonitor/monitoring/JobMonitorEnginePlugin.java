package org.cibseven.getstarted.jobmonitor.monitoring;

import java.util.ArrayList;
import java.util.List;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.spring.boot.starter.util.SpringBootProcessEnginePlugin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ProcessEnginePlugin that registers our JobMonitorBpmnParseListener
 * as a preParseListener at engine startup.
 *
 * preParse means: the listener takes effect BEFORE parsing and can
 * attach an ExecutionListener to every activity. It then runs
 * automatically for every BPMN model that gets deployed — without
 * touching the BPMN files.
 */
@Component
public class JobMonitorEnginePlugin extends SpringBootProcessEnginePlugin {

  private final ThreadContextRegistry registry;
  private final boolean logLifecycle;

  public JobMonitorEnginePlugin(
      ThreadContextRegistry registry,
      @Value("${jobmonitor.log-job-lifecycle:true}") boolean logLifecycle) {
    this.registry = registry;
    this.logLifecycle = logLifecycle;
  }

  @Override
  public void preInit(ProcessEngineConfigurationImpl cfg) {
    List<BpmnParseListener> preParse = cfg.getCustomPreBPMNParseListeners();
    if (preParse == null) {
      preParse = new ArrayList<>();
      cfg.setCustomPreBPMNParseListeners(preParse);
    }
    preParse.add(new JobMonitorBpmnParseListener(
        new JobMonitorExecutionListener(registry, logLifecycle)));
  }
}
