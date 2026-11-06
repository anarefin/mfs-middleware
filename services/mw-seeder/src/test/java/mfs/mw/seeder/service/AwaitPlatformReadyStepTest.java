package mfs.mw.seeder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import mfs.mw.seeder.exception.RemoteCallException;
import org.junit.jupiter.api.Test;

class AwaitPlatformReadyStepTest {

  private final Set<String> up = new HashSet<>();
  private final AwaitPlatformReadyStep step =
      new AwaitPlatformReadyStep((URI url) -> up.contains(url.getHost()), SeedFixtures.props(Path.of("unused")));

  @Test
  void readyWhenEveryProbeAnswersAndChangesNothing() {
    up.addAll(Set.of("apisix", "mw-admin-api"));

    assertThat(step.run(new SeedContext())).isEqualTo(StepResult.unchanged(2));
  }

  @Test
  void aServiceNotReadyYetIsARetryableFailureNamingIt() {
    up.add("apisix");

    assertThatThrownBy(() -> step.run(new SeedContext()))
        .isInstanceOfSatisfying(RemoteCallException.class, e -> assertThat(e.retryable()).isTrue())
        .hasMessageContaining("mw-admin-api")
        .hasMessageNotContaining("apisix");
  }
}
