package mfs.mw.adapter.telco.partner.template;

import io.github.resilience4j.timelimiter.TimeLimiter;
import java.net.http.HttpClient;
import mfs.mw.adapter.telco.partner.AuthScheme;
import mfs.mw.adapter.telco.partner.CredentialRef;
import mfs.mw.adapter.telco.partner.PartnerAdapter;
import mfs.mw.adapter.telco.partner.config.PartnerConfig;

/**
 * One partner config, ready to serve calls: its adapter, auth scheme, HTTP client and time
 * limiter are built once per config version and replaced as a whole when the config changes.
 *
 * @param id partner instance id ({@code ocs}, {@code gp}, ...)
 * @param version config version ({@code 0} for the seed from application config)
 * @param resilience Resilience4j instance name of the bulkhead and circuit breaker
 */
public record PartnerInstance(
    String id,
    long version,
    PartnerConfig config,
    PartnerAdapter adapter,
    AuthScheme auth,
    CredentialRef credentials,
    HttpClient http,
    TimeLimiter timeLimiter,
    String resilience) {}
