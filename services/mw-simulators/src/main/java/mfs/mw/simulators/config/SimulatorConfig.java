package mfs.mw.simulators.config;

import java.security.SecureRandom;
import java.time.Clock;
import mfs.mw.simulators.filter.RecordingFilter;
import mfs.mw.simulators.service.RequestRecorder;
import mfs.mw.simulators.service.ScenarioStore;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SimulatorConfig {

  /** Registered here, not scanned, so controller slice tests do not need its collaborators. */
  @Bean
  FilterRegistrationBean<RecordingFilter> recordingFilter(
      ScenarioStore scenarios, RequestRecorder recorder, Clock clock) {
    FilterRegistrationBean<RecordingFilter> reg =
        new FilterRegistrationBean<>(new RecordingFilter(scenarios, recorder, clock));
    reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return reg;
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
