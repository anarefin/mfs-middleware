package mfs.mw.simulators.config;

import java.time.Clock;
import mfs.mw.simulators.filter.RecordingFilter;
import mfs.mw.simulators.service.mockcontrol.Journal;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

/** The mock-control journal's filter, only in the {@value MockControl#PROFILE} profile (ADR D33). */
@Configuration(proxyBeanMethods = false)
@Profile(MockControl.PROFILE)
public class MockControlConfig {

  /** Registered here, not scanned, so controller slice tests do not need its collaborators. */
  @Bean
  FilterRegistrationBean<RecordingFilter> recordingFilter(Journal journal, Clock clock) {
    FilterRegistrationBean<RecordingFilter> reg = new FilterRegistrationBean<>(new RecordingFilter(journal, clock));
    reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return reg;
  }
}
