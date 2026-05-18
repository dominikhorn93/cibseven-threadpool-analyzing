package org.cibseven.getstarted.jobmonitor.delegate;

import java.util.concurrent.ThreadLocalRandom;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * "Slow" delegate that pins a job-executor thread for a configurable
 * amount of time via sleep() — handy to provoke overflow scenarios.
 *
 * Configured per process instance via the variable {@code sleepMs}
 * (Long, default 3000). The LoadGeneratorController sets that variable.
 *
 * If {@code failProbability} (Double, 0..1) is set the delegate throws
 * a RuntimeException with that probability → the JobExecutor records
 * retries and eventually creates incidents. That path is worth
 * observing too.
 */
@Component("slowDelegate")
public class SlowDelegate implements JavaDelegate {

  private static final Logger LOG = LoggerFactory.getLogger(SlowDelegate.class);

  @Override
  public void execute(DelegateExecution execution) throws Exception {
    long sleepMs = asLong(execution.getVariable("sleepMs"), 3000L);
    double failProb = asDouble(execution.getVariable("failProbability"), 0.0);

    LOG.info("slowDelegate sleep {} ms (activity={}, pi={})",
        sleepMs, execution.getCurrentActivityId(), execution.getProcessInstanceId());
    Thread.sleep(sleepMs);

    if (failProb > 0 && ThreadLocalRandom.current().nextDouble() < failProb) {
      LOG.warn("slowDelegate simulating failure at activity={}", execution.getCurrentActivityId());
      throw new RuntimeException("simulated failure in " + execution.getCurrentActivityId());
    }
  }

  private static long asLong(Object v, long dflt) {
    if (v instanceof Number n) return n.longValue();
    if (v instanceof String s) try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
    return dflt;
  }
  private static double asDouble(Object v, double dflt) {
    if (v instanceof Number n) return n.doubleValue();
    if (v instanceof String s) try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
    return dflt;
  }
}
