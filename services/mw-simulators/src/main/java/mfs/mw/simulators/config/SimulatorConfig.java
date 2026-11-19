package mfs.mw.simulators.config;

import java.security.SecureRandom;
import mfs.mw.simulators.filter.MockAbortValve;
import org.springframework.boot.tomcat.TomcatContextCustomizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SimulatorConfig {

  /** Mock control's unanswered connections (switch DOWN, CONNECTION_RESET; ticket 102). */
  @Bean
  TomcatContextCustomizer mockAbortValve() {
    return context -> context.getPipeline().addValve(new MockAbortValve());
  }

  /** Draws for the scenario error rate. */
  @Bean
  RandomGenerator simulatorRandom() {
    return new SecureRandom();
  }

  /** Runs callbacks off the request thread. */
  @Bean(destroyMethod = "close")
  ExecutorService callbackExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
