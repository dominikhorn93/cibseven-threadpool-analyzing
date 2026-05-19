package org.cibseven.getstarted.jobmonitor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * On-demand JSON view of the JobExecutor state.
 *
 *   GET /actuator/jobexecutor
 *
 * Same data the overflow block prints, exposed live so monitoring scripts
 * can poll it without grepping logs.
 */
@Component
@Endpoint(id = "jobexecutor")
public class JobExecutorEndpoint {

  private final ThreadPoolTaskExecutor te;

  public JobExecutorEndpoint(@Qualifier("camundaTaskExecutor") ThreadPoolTaskExecutor te) {
    this.te = te;
  }

  @ReadOperation
  public Map<String, Object> snapshot() {
    ThreadPoolExecutor tpe = te.getThreadPoolExecutor();
    Map<String, Object> pool = new LinkedHashMap<>();
    pool.put("active", tpe.getActiveCount());
    pool.put("size", tpe.getPoolSize());
    pool.put("core", te.getCorePoolSize());
    pool.put("max", te.getMaxPoolSize());
    pool.put("completed", tpe.getCompletedTaskCount());

    Map<String, Object> queue = new LinkedHashMap<>();
    queue.put("size", tpe.getQueue().size());
    queue.put("capacity", te.getQueueCapacity());
    queue.put("remaining", tpe.getQueue().remainingCapacity());

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("pool", pool);
    out.put("queue", queue);
    out.put("running", ActivityTracker.running());
    return out;
  }
}
