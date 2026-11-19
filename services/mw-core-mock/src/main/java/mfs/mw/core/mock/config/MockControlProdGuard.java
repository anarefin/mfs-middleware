package mfs.mw.core.mock.config;

import mfs.mw.core.mock.service.mockcontrol.MockControl;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * FRS MCT-08 (ticket 103): the mock-control profile never runs in production. Exists only when the
 * profile is active and refuses to start when {@code mw.service.env} ({@code MW_ENV}) is {@code prod}
 * (any case), so a misconfigured production deploy fails at start instead of serving the control API.
 */
@Configuration(proxyBeanMethods = false)
@Profile(MockControl.PROFILE)
public class MockControlProdGuard {

  public MockControlProdGuard(Environment env) {
    String name = env.getProperty("mw.service.env", env.getProperty("MW_ENV", ""));
    if ("prod".equalsIgnoreCase(name.strip()) || "production".equalsIgnoreCase(name.strip())) {
      throw new IllegalStateException(
          "profile " + MockControl.PROFILE + " is test-only and must not be active with MW_ENV=" + name + " (FRS MCT-08)");
    }
  }
}
