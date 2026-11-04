package mfs.mw.adapter.telco.partner.template;

import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import mfs.mw.adapter.telco.partner.CredentialRef;
import mfs.mw.adapter.telco.partner.CredentialResolver;
import mfs.mw.adapter.telco.partner.auth.AuthSchemes;
import mfs.mw.adapter.telco.partner.config.PartnerConfig;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Builds a {@link PartnerInstance} from a validated {@link PartnerConfig}. */
@Component
public class PartnerInstanceFactory {

  private final CredentialResolver credentials;
  private final Clock clock;
  private final JsonMapper json;

  public PartnerInstanceFactory(CredentialResolver credentials, Clock clock, JsonMapper json) {
    this.credentials = credentials;
    this.clock = clock;
    this.json = json;
  }

  /**
   * @throws IllegalArgumentException listing what is wrong with the config
   */
  public PartnerInstance create(String id, long version, PartnerConfig config) {
    List<String> problems = config.problems();
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException("partner " + id + ": " + String.join("; ", problems));
    }
    TimeLimiter limiter =
        TimeLimiter.of(
            "partner-" + id,
            TimeLimiterConfig.custom().timeoutDuration(config.timeout()).cancelRunningFuture(true).build());
    HttpClient http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
    String resilience = config.resilience() == null || config.resilience().isBlank() ? id : config.resilience();
    CredentialRef ref =
        config.auth().credentialsRef() == null ? CredentialRef.NONE : new CredentialRef(config.auth().credentialsRef());
    return new PartnerInstance(
        id,
        version,
        config,
        new RestJsonAdapter(config.template(), json),
        AuthSchemes.create(config.auth(), credentials, clock),
        ref,
        http,
        limiter,
        resilience);
  }
}
