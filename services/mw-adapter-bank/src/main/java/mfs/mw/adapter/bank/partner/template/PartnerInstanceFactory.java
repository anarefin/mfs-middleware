package mfs.mw.adapter.bank.partner.template;

import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import mfs.mw.adapter.bank.partner.CredentialRef;
import mfs.mw.adapter.bank.partner.CredentialResolver;
import mfs.mw.adapter.bank.partner.auth.AuthSchemes;
import mfs.mw.adapter.bank.partner.config.PartnerConfig;
import mfs.mw.adapter.bank.partner.config.PartnerConfigRefusedException;
import mfs.mw.adapter.bank.partner.config.PartnerEndpointPolicy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Builds a {@link PartnerInstance} from a validated {@link PartnerConfig}. */
@Component
public class PartnerInstanceFactory {

  private final CredentialResolver credentials;
  private final Clock clock;
  private final JsonMapper json;
  private final PartnerEndpointPolicy policy;

  public PartnerInstanceFactory(
      CredentialResolver credentials, Clock clock, JsonMapper json, PartnerEndpointPolicy policy) {
    this.credentials = credentials;
    this.clock = clock;
    this.json = json;
    this.policy = policy;
  }

  /**
   * Validates the config (completeness, mappings compile) and the security rules (endpoint
   * allowlist, https, credentials refs), then builds the instance.
   *
   * @throws PartnerConfigRefusedException listing what is wrong with the config
   */
  public PartnerInstance create(String id, long version, PartnerConfig config) {
    List<String> security = new ArrayList<>(policy.problems(id, config.baseUrl()));
    List<String> problems = new ArrayList<>(config.problems());
    problems.stream()
        .filter(p -> p.startsWith(PartnerConfig.SECURITY))
        .map(p -> p.substring(PartnerConfig.SECURITY.length()))
        .forEach(security::add);
    problems.removeIf(p -> p.startsWith(PartnerConfig.SECURITY));
    if (!security.isEmpty() || !problems.isEmpty()) {
      List<String> all = new ArrayList<>(security);
      all.addAll(problems);
      throw new PartnerConfigRefusedException(id, all, !security.isEmpty());
    }
    TimeLimiter limiter =
        TimeLimiter.of(
            "partner-" + id,
            TimeLimiterConfig.custom().timeoutDuration(config.timeout()).cancelRunningFuture(true).build());
    // never follow a redirect: it could carry the request and its credentials to another host
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    String resilience = config.resilience() == null || config.resilience().isBlank() ? id : config.resilience();
    CredentialRef ref =
        config.auth().credentialsRef() == null
            ? CredentialRef.NONE
            : CredentialRef.of(id, config.auth().credentialsRef());
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
