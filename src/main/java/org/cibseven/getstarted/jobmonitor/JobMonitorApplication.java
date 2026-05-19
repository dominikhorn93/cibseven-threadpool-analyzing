package org.cibseven.getstarted.jobmonitor;

import java.util.Map;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.spring.boot.starter.annotation.EnableProcessApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Boot main + the bare-bones demo bits (slow delegate, load generator).
 * Wiring of the JobExecutor instrumentation lives in {@link JobMonitoring}
 */
@SpringBootApplication
@EnableProcessApplication
public class JobMonitorApplication {

  public static void main(String[] args) {
    SpringApplication.run(JobMonitorApplication.class, args);
  }

  /** Pins a job-executor thread for {@code sleepMs}; used by overflow-demo.bpmn. */
  @Component("slowDelegate")
  static class SlowDelegate implements JavaDelegate {
    @Override public void execute(DelegateExecution e) throws Exception {
      Object v = e.getVariable("sleepMs");
      Thread.sleep(v instanceof Number n ? n.longValue() : 3000L);
    }
  }

  /** POST /demo/overflow?count=30&sleepMs=4000 — provokes queue overflow. */
  @RestController
  static class LoadGenerator {
    private final RuntimeService rs;
    LoadGenerator(RuntimeService rs) { this.rs = rs; }

    @PostMapping("/demo/overflow")
    public Map<String, Object> overflow(
        @RequestParam(defaultValue = "30") int count,
        @RequestParam(defaultValue = "3000") long sleepMs) {
      for (int i = 0; i < count; i++) {
        rs.startProcessInstanceByKey("overflowDemo",
            "load-" + System.currentTimeMillis() + "-" + i,
            Map.of("sleepMs", sleepMs));
      }
      return Map.of("started", count, "sleepMs", sleepMs,
          "hint", "watch the BATCH/OVERFLOW log lines and /actuator/prometheus");
    }
  }
}
