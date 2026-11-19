package mfs.mw.audit.dw.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The signed daily audit anchor (spec 14.3, ticket 74) under {@code mw.audit-dw.anchor}, and the
 * mw-crypto client that signs it under {@code mw.audit-dw.anchor.crypto}.
 *
 * @param enabled whether the anchor job, its verification and the mw-crypto client exist
 * @param cron Spring cron (UTC) of the job; it signs every past day not yet anchored, so an hourly
 *     run retries soon after a failure ({@code -} switches the schedule off)
 * @param exportDir folder the anchors are also written to as {@code <date>.jws} (WORM target TBC);
 *     blank = no export
 * @param catchUpDays most days anchored in one run
 * @param grace how long after a UTC day ends its anchor may still be missing before the
 *     verification calls it missing (covers the next hourly run and a short mw-crypto outage)
 * @param crypto the mw-crypto call
 */
@ConfigurationProperties("mw.audit-dw.anchor")
public record AnchorProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("0 15 * * * *") String cron,
    String exportDir,
    @DefaultValue("31") int catchUpDays,
    @DefaultValue("3h") Duration grace,
    @DefaultValue Crypto crypto) {

  /**
   * mw-crypto ({@code https://mw-crypto:8080}, mTLS with this service's certificate when {@code
   * mw.tls.enabled}) and the Keycloak realm {@code services} client-credentials token it needs.
   *
   * @param baseUrl mw-crypto's internal API
   * @param tokenUri Keycloak token endpoint
   * @param clientId this service's client ({@code azp} = certificate CN)
   * @param clientSecret from Vault ({@code secret/mw-audit-dw}), never in config files
   * @param connectTimeout connect timeout of both calls
   * @param readTimeout request timeout of both calls
   */
  public record Crypto(
      @DefaultValue("https://mw-crypto:8080") URI baseUrl,
      @DefaultValue("http://keycloak:8080/realms/services/protocol/openid-connect/token") URI tokenUri,
      @DefaultValue("mw-audit-dw") String clientId,
      String clientSecret,
      @DefaultValue("2s") Duration connectTimeout,
      @DefaultValue("10s") Duration readTimeout) {

    @Override
    public String toString() {
      return "Crypto[baseUrl=" + baseUrl + ", tokenUri=" + tokenUri + ", clientId=" + clientId
          + ", clientSecret=****, connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
  }
}
