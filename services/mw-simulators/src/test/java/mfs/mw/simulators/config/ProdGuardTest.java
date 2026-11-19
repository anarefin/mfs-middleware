package mfs.mw.simulators.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** FRS MCT-08 (ticket 103): the mock-control profile refuses to start in production. */
class ProdGuardTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(MockControlProdGuard.class);

  @Test
  void mockControlWithProdEnvFailsToStart() {
    runner.withPropertyValues("spring.profiles.active=mock-control", "mw.service.env=prod")
        .run(ctx -> assertThat(ctx).hasFailed()
            .getFailure().rootCause().hasMessageContaining("MW_ENV=prod").hasMessageContaining("MCT-08"));
  }

  @Test
  void mockControlOutsideProdStarts() {
    runner.withPropertyValues("spring.profiles.active=mock-control", "mw.service.env=local")
        .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(MockControlProdGuard.class));
  }

  @Test
  void prodWithoutTheProfileHasNoGuardAndStarts() {
    new ApplicationContextRunner().withUserConfiguration(MockControlProdGuard.class)
        .withPropertyValues("spring.profiles.active=", "mw.service.env=prod")
        .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(MockControlProdGuard.class));
  }
}
