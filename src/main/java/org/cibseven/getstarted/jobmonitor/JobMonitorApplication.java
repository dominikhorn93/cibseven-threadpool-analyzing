package org.cibseven.getstarted.jobmonitor;

import org.cibseven.bpm.spring.boot.starter.annotation.EnableProcessApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableProcessApplication
@EnableScheduling
public class JobMonitorApplication {

  public static void main(String... args) {
    SpringApplication.run(JobMonitorApplication.class, args);
  }
}
