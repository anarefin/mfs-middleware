package mfs.mw.admin.api.config;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Partner routes as config (ADR D26, ticket 83), maker side.
 *
 * @param kamelets the mw-* Kamelets the adapters ship ({@code classpath:kamelets} of
 *     mw-adapter-billpay); a route naming another one is refused at submit. Keep it in step with
 *     the adapters' library: a new Kamelet ships with the adapter first, then here
 */
@ConfigurationProperties("mw.partner-routes")
public record PartnerRouteProperties(List<String> kamelets) {

  /** The library of ticket 82, and mw-soap-call (ticket 85). */
  public static final Set<String> LIBRARY =
      Set.of("mw-audit", "mw-auth-apikey", "mw-auth-hmac-sha256", "mw-auth-none", "mw-classify", "mw-http-call", "mw-map",
          "mw-soap-call");

  public PartnerRouteProperties {
    kamelets = kamelets == null || kamelets.isEmpty() ? List.copyOf(new java.util.TreeSet<>(LIBRARY)) : List.copyOf(kamelets);
  }
}
