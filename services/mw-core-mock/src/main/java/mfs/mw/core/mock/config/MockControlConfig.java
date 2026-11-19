package mfs.mw.core.mock.config;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import mfs.mw.core.mock.filter.MockAbortValve;
import mfs.mw.core.mock.filter.MockControlFilter;
import mfs.mw.core.mock.service.mockcontrol.MockControl;
import mfs.mw.core.mock.service.mockcontrol.ScenarioEngine;
import org.springframework.boot.tomcat.TomcatContextCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core's mock control (ADR D33, ticket 103), only in the {@value MockControl#PROFILE} profile: the
 * {@code /core/v1} filter (after the security chain, so an unauthenticated call is refused as ever),
 * the Tomcat valve that drops a connection, and the engine's random draws (switch ratio, latency).
 */
@Configuration(proxyBeanMethods = false)
@Profile(MockControl.PROFILE)
public class MockControlConfig {

  @Bean
  FilterRegistrationBean<MockControlFilter> mockControlFilter(ScenarioEngine engine, JsonMapper json) {
    FilterRegistrationBean<MockControlFilter> reg = new FilterRegistrationBean<>(new MockControlFilter(engine, json));
    reg.addUrlPatterns("/core/v1/*");
    reg.setOrder(0); // the security filter chain is at -100
    return reg;
  }

  /** Mock control's unanswered connections (switch DOWN, CONNECTION_RESET). */
  @Bean
  TomcatContextCustomizer mockAbortValve() {
    return context -> context.getPipeline().addValve(new MockAbortValve());
  }

  @Bean
  RandomGenerator mockControlRandom() {
    return new SecureRandom();
  }
}
