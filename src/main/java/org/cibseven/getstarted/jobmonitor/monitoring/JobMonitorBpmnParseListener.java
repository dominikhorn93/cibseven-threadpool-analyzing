package org.cibseven.getstarted.jobmonitor.monitoring;

import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.util.xml.Element;

/**
 * Attaches our JobMonitorExecutionListener to every activity in the
 * BPMN parse tree, both for START and END events.
 *
 * This is the canonical way in Camunda / CIB seven to "hook everything
 * globally without forcing the BPMN author to add listeners manually".
 *
 * We override the relevant parse methods: ServiceTask, UserTask,
 * ScriptTask, IntermediateThrowEvent, CallActivity, BusinessRuleTask,
 * ParallelGateway, ExclusiveGateway, StartEvent, EndEvent, ...
 */
public class JobMonitorBpmnParseListener extends AbstractBpmnParseListener {

  private final JobMonitorExecutionListener listener;

  public JobMonitorBpmnParseListener(JobMonitorExecutionListener listener) {
    this.listener = listener;
  }

  private void attach(ActivityImpl activity) {
    activity.addListener(ExecutionListener.EVENTNAME_START, listener);
    activity.addListener(ExecutionListener.EVENTNAME_END,   listener);
  }

  @Override public void parseServiceTask(Element s, ScopeImpl scope, ActivityImpl a)         { attach(a); }
  @Override public void parseUserTask(Element s, ScopeImpl scope, ActivityImpl a)            { attach(a); }
  @Override public void parseScriptTask(Element s, ScopeImpl scope, ActivityImpl a)          { attach(a); }
  @Override public void parseBusinessRuleTask(Element s, ScopeImpl scope, ActivityImpl a)    { attach(a); }
  @Override public void parseTask(Element s, ScopeImpl scope, ActivityImpl a)                { attach(a); }
  @Override public void parseManualTask(Element s, ScopeImpl scope, ActivityImpl a)          { attach(a); }
  @Override public void parseSendTask(Element s, ScopeImpl scope, ActivityImpl a)            { attach(a); }
  @Override public void parseReceiveTask(Element s, ScopeImpl scope, ActivityImpl a)         { attach(a); }
  @Override public void parseCallActivity(Element s, ScopeImpl scope, ActivityImpl a)        { attach(a); }
  @Override public void parseStartEvent(Element s, ScopeImpl scope, ActivityImpl a)          { attach(a); }
  @Override public void parseEndEvent(Element s, ScopeImpl scope, ActivityImpl a)            { attach(a); }
  @Override public void parseIntermediateCatchEvent(Element s, ScopeImpl scope, ActivityImpl a) { attach(a); }
  @Override public void parseIntermediateThrowEvent(Element s, ScopeImpl scope, ActivityImpl a) { attach(a); }
  @Override public void parseExclusiveGateway(Element s, ScopeImpl scope, ActivityImpl a)    { attach(a); }
  @Override public void parseInclusiveGateway(Element s, ScopeImpl scope, ActivityImpl a)    { attach(a); }
  @Override public void parseParallelGateway(Element s, ScopeImpl scope, ActivityImpl a)     { attach(a); }
  @Override public void parseEventBasedGateway(Element s, ScopeImpl scope, ActivityImpl a)   { attach(a); }
  @Override public void parseBoundaryEvent(Element s, ScopeImpl scopeElement, ActivityImpl a){ attach(a); }
  @Override public void parseSubProcess(Element s, ScopeImpl scope, ActivityImpl a)          { attach(a); }
  @Override public void parseTransaction(Element s, ScopeImpl scope, ActivityImpl a)         { attach(a); }
  @Override public void parseMultiInstanceLoopCharacteristics(Element s, Element mi, ActivityImpl a) { attach(a); }
}
