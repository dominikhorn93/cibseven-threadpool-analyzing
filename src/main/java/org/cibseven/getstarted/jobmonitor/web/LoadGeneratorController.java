package org.cibseven.getstarted.jobmonitor.web;

import java.util.HashMap;
import java.util.Map;
import org.cibseven.bpm.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints that put the JobExecutor under pressure.
 *
 *   POST /demo/overflow?count=50&sleepMs=3000&failProbability=0
 *
 * Starts {@code count} instances of overflowDemo as fast as it can. Each
 * PI produces 1 + 5 = 6 async jobs. With the application.yaml defaults
 * (pool=3, queue=5) the queue is full after the first few PIs, and you
 * see the LoggingRejectedJobsHandler output plus the BATCH SUBMITTED /
 * BATCH START / BATCH DONE log lines.
 */
@RestController
@RequestMapping("/demo")
public class LoadGeneratorController {

  private static final Logger LOG = LoggerFactory.getLogger(LoadGeneratorController.class);

  private final RuntimeService runtimeService;

  public LoadGeneratorController(RuntimeService runtimeService) {
    this.runtimeService = runtimeService;
  }

  @PostMapping("/overflow")
  public Map<String, Object> overflow(
      @RequestParam(defaultValue = "50") int count,
      @RequestParam(defaultValue = "3000") long sleepMs,
      @RequestParam(defaultValue = "0.0") double failProbability) {

    LOG.warn("LOAD GENERATOR  count={} sleepMs={} failProb={}",
        count, sleepMs, failProbability);

    int started = 0;
    long t0 = System.currentTimeMillis();
    for (int i = 0; i < count; i++) {
      Map<String, Object> vars = new HashMap<>();
      vars.put("sleepMs", sleepMs);
      vars.put("failProbability", failProbability);
      runtimeService.startProcessInstanceByKey(
          "overflowDemo", "load-" + System.currentTimeMillis() + "-" + i, vars);
      started++;
    }
    long dt = System.currentTimeMillis() - t0;
    LOG.warn("LOAD GENERATOR done. started={} durationMs={}", started, dt);

    return Map.of(
        "started", started,
        "durationMs", dt,
        "sleepMs", sleepMs,
        "failProbability", failProbability,
        "hint", "see /monitor/snapshot, /actuator/prometheus, logs (BATCH SUBMITTED, JOBEXECUTOR QUEUE OVERFLOW)"
    );
  }
}
