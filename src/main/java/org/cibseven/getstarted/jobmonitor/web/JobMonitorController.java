package org.cibseven.getstarted.jobmonitor.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.impl.jobexecutor.JobExecutor;
import org.cibseven.bpm.engine.runtime.Job;
import org.cibseven.getstarted.jobmonitor.monitoring.InstrumentedTaskExecutor;
import org.cibseven.getstarted.jobmonitor.monitoring.JobBatchInfo;
import org.cibseven.getstarted.jobmonitor.monitoring.LoggingRejectedJobsHandler;
import org.cibseven.getstarted.jobmonitor.monitoring.ThreadContextRegistry;
import org.cibseven.getstarted.jobmonitor.monitoring.ThreadJobContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON endpoints for analysis:
 *
 *   GET /monitor/snapshot          full overall state
 *   GET /monitor/threads           what each thread is currently doing
 *   GET /monitor/batches           in-flight batches (TaskExecutor view)
 *   GET /monitor/history           ring buffer of completed / rejected batches
 *   GET /monitor/queue             plain pool/queue view
 *   GET /monitor/db-jobs?limit=50  jobs from the engine DB
 */
@RestController
@RequestMapping("/monitor")
public class JobMonitorController {

  private final InstrumentedTaskExecutor taskExecutor;
  private final JobExecutor jobExecutor;
  private final ThreadContextRegistry registry;
  private final LoggingRejectedJobsHandler rejectedHandler;
  private final ManagementService managementService;

  public JobMonitorController(
      InstrumentedTaskExecutor taskExecutor,
      JobExecutor jobExecutor,
      ThreadContextRegistry registry,
      LoggingRejectedJobsHandler rejectedHandler,
      ManagementService managementService) {
    this.taskExecutor = taskExecutor;
    this.jobExecutor = jobExecutor;
    this.registry = registry;
    this.rejectedHandler = rejectedHandler;
    this.managementService = managementService;
  }

  @GetMapping("/snapshot")
  public Map<String, Object> snapshot() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("pool", poolView());
    out.put("acquisition", acquisitionView());
    out.put("engineJobs", engineJobsView());
    out.put("liveThreads", registry.liveThreads());
    out.put("liveBatches", registry.liveBatches());
    out.put("recentHistory", registry.recentHistory());
    return out;
  }

  @GetMapping("/threads")
  public List<ThreadJobContext> threads()      { return List.copyOf(registry.liveThreads()); }

  @GetMapping("/batches")
  public List<JobBatchInfo> liveBatches()      { return List.copyOf(registry.liveBatches()); }

  @GetMapping("/history")
  public List<JobBatchInfo> history()          { return List.copyOf(registry.recentHistory()); }

  @GetMapping("/queue")
  public Map<String, Object> queue()           { return poolView(); }

  @GetMapping("/db-jobs")
  public List<Map<String, Object>> dbJobs() {
    List<Job> jobs = managementService.createJobQuery().listPage(0, 200);
    return jobs.stream().map(j -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", j.getId());
      m.put("processInstanceId", j.getProcessInstanceId());
      m.put("processDefinitionKey", j.getProcessDefinitionKey());
      m.put("dueDate", j.getDuedate());
      m.put("retries", j.getRetries());
      m.put("exceptionMessage", j.getExceptionMessage());
      m.put("priority", j.getPriority());
      m.put("suspended", j.isSuspended());
      return m;
    }).collect(Collectors.toList());
  }

  private Map<String, Object> poolView() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("active", taskExecutor.getActiveCount());
    m.put("poolSize", taskExecutor.getPoolSize());
    m.put("corePoolSize", taskExecutor.getCorePoolSize());
    m.put("maxPoolSize", taskExecutor.getMaxPoolSize());
    m.put("queueSize", taskExecutor.getQueueSize());
    m.put("queueCapacity", taskExecutor.getQueueCapacity());
    m.put("queueRemaining", taskExecutor.getQueueRemainingCapacity());
    m.put("queueHighWater", taskExecutor.getQueueHighWater());
    m.put("activeHighWater", taskExecutor.getActiveHighWater());
    m.put("submitted", taskExecutor.getSubmittedCount());
    m.put("completed", taskExecutor.getCompletedCount());
    m.put("rejectedExecutions", taskExecutor.getRejectedCount());
    m.put("rejectedJobsTotal", rejectedHandler.getRejectedJobsTotal());
    return m;
  }

  private Map<String, Object> acquisitionView() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", jobExecutor.getName());
    m.put("active", jobExecutor.isActive());
    m.put("maxJobsPerAcquisition", jobExecutor.getMaxJobsPerAcquisition());
    m.put("waitTimeInMillis", jobExecutor.getWaitTimeInMillis());
    m.put("lockTimeInMillis", jobExecutor.getLockTimeInMillis());
    m.put("lockOwner", jobExecutor.getLockOwner());
    return m;
  }

  private Map<String, Object> engineJobsView() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("totalJobs", managementService.createJobQuery().count());
    m.put("executable", managementService.createJobQuery().executable().count());
    m.put("withException", managementService.createJobQuery().withException().count());
    m.put("withRetriesLeft", managementService.createJobQuery().withRetriesLeft().count());
    m.put("noRetriesLeft", managementService.createJobQuery().noRetriesLeft().count());
    m.put("suspended", managementService.createJobQuery().suspended().count());
    return m;
  }
}
