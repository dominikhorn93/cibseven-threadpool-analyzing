package org.cibseven.getstarted.jobmonitor.config;

import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import org.cibseven.bpm.engine.impl.jobexecutor.JobExecutor;
import org.cibseven.getstarted.jobmonitor.monitoring.InstrumentedSpringJobExecutor;
import org.cibseven.getstarted.jobmonitor.monitoring.InstrumentedTaskExecutor;
import org.cibseven.getstarted.jobmonitor.monitoring.LoggingRejectedJobsHandler;
import org.cibseven.getstarted.jobmonitor.monitoring.ThreadContextRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;

/**
 * Wires up our instrumented implementations.
 *
 * We override the beans the cibseven-bpm-spring-boot starter ships by
 * default. Both are registered there with @ConditionalOnMissingBean so
 * our beans win without any extra ceremony.
 *
 * Values are read straight from camunda.bpm.job-execution.* (the same
 * properties the starter would use) so application.yaml remains the
 * single source of truth.
 */
@Configuration
public class JobExecutorConfig {

  @Value("${camunda.bpm.job-execution.core-pool-size:3}")  private int corePoolSize;
  @Value("${camunda.bpm.job-execution.max-pool-size:10}")  private int maxPoolSize;
  @Value("${camunda.bpm.job-execution.queue-capacity:3}")  private int queueCapacity;
  @Value("${camunda.bpm.job-execution.keep-alive-seconds:60}") private int keepAliveSeconds;
  @Value("${camunda.bpm.job-execution.max-jobs-per-acquisition:3}") private int maxJobsPerAcquisition;
  @Value("${camunda.bpm.job-execution.wait-time-in-millis:5000}")   private int waitTimeMs;
  @Value("${camunda.bpm.job-execution.lock-time-in-millis:300000}") private int lockTimeMs;

  /**
   * "camundaTaskExecutor" is the bean name the SpringJobExecutor in
   * cibseven-bpm-spring-boot expects via @Qualifier.
   */
  @Bean(name = "camundaTaskExecutor", destroyMethod = "shutdown")
  public InstrumentedTaskExecutor camundaTaskExecutor() {
    InstrumentedTaskExecutor te = new InstrumentedTaskExecutor();
    te.setCorePoolSize(corePoolSize);
    te.setMaxPoolSize(maxPoolSize);
    te.setQueueCapacity(queueCapacity);
    te.setKeepAliveSeconds(keepAliveSeconds);
    te.setThreadNamePrefix("jobExecutor-");
    // SpringJobExecutor relies on a RejectedExecutionException being
    // thrown so it can dispatch to the RejectedJobsHandler.
    te.setRejectedExecutionHandler(new AbortPolicy());
    te.initialize();
    return te;
  }

  @Bean
  public LoggingRejectedJobsHandler loggingRejectedJobsHandler(
      ThreadContextRegistry registry,
      InstrumentedTaskExecutor te) {
    return new LoggingRejectedJobsHandler(registry, te);
  }

  @Bean
  public JobExecutor jobExecutor(
      ThreadContextRegistry registry,
      InstrumentedTaskExecutor te,
      LoggingRejectedJobsHandler rejectedHandler) {

    InstrumentedSpringJobExecutor je = new InstrumentedSpringJobExecutor(registry);
    je.setTaskExecutor(te);
    je.setRejectedJobsHandler(rejectedHandler);
    je.setMaxJobsPerAcquisition(maxJobsPerAcquisition);
    je.setWaitTimeInMillis(waitTimeMs);
    je.setLockTimeInMillis(lockTimeMs);
    return je;
  }
}
