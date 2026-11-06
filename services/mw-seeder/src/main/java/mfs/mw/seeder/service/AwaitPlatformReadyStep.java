package mfs.mw.seeder.service;

import java.util.List;
import mfs.mw.seeder.client.HealthProbe;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.config.SeederProperties.Readiness;
import mfs.mw.seeder.exception.RemoteCallException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * await-platform-ready: APISIX ({@code /status/ready}), Keycloak ({@code /health/ready}) and the
 * readiness of every mw-* service in {@code mw.seeder.readiness}. Not ready yet is a retryable
 * failure, so the runner probes again with back-off until the step's timeout. Compose already
 * starts the seeder only once the services are healthy; this covers a seeder run on its own.
 */
@Service
@Order(1)
public class AwaitPlatformReadyStep implements SeedStep {

  private final HealthProbe probe;
  private final SeederProperties props;

  public AwaitPlatformReadyStep(HealthProbe probe, SeederProperties props) {
    this.probe = probe;
    this.props = props;
  }

  @Override
  public String id() {
    return "await-platform-ready";
  }

  @Override
  public StepResult run(SeedContext ctx) {
    List<String> waiting =
        props.readiness().stream().filter(r -> !probe.ready(r.url())).map(Readiness::name).toList();
    if (!waiting.isEmpty()) {
      throw new RemoteCallException(503, "not ready yet: " + String.join(", ", waiting));
    }
    return StepResult.unchanged(props.readiness().size());
  }
}
