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

  /** The library of ticket 82, the auth Kamelets of ticket 87 and mw-soap-call (ticket 85). */
  public static final Set<String> LIBRARY =
      Set.of("mw-audit", "mw-auth-apikey", "mw-auth-hmac-sha256", "mw-auth-none", "mw-classify", "mw-http-call", "mw-map",
          "mw-auth-basic", "mw-auth-oauth2-cc", "mw-auth-jws", "mw-soap-call");

  /** The batch Kamelets of ticket 86 (SFTP source and sink, reconciliation lines). */
  public static final Set<String> BATCH = Set.of("mw-recon", "mw-sftp-sink", "mw-sftp-source");

  public PartnerRouteProperties {
    java.util.TreeSet<String> library = new java.util.TreeSet<>(LIBRARY);
    library.addAll(BATCH);
    kamelets = kamelets == null || kamelets.isEmpty() ? List.copyOf(library) : List.copyOf(kamelets);
  }
}
