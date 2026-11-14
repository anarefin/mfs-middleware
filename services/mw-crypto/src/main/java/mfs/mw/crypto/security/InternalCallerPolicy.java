package mfs.mw.crypto.security;

import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The caller rules of {@code /internal/v1/**} (spec 18.2, 18.8, D24; copied from mw-audit-dw, D14).
 * A token must name {@code mw-crypto} in {@code aud} ({@link #audience}); the calling client ({@code
 * azp}, Keycloak's client id of a client-credentials token) must be on the allow-list and, with mTLS
 * on ({@code mw.tls.enabled}), equal the CN of the client certificate the TLS handshake verified
 * (the JWT stays required: mTLS is in addition). Generating and rotating keys is narrower ({@link
 * #allowsKeyAdmin}): only the key-admin clients, and generation only while the dev key bootstrap is
 * on, until the key ceremony (ticket 75) takes over. Every refusal logs WARN {@code
 * security.internal_call_denied} with the client and a reason.
 */
public final class InternalCallerPolicy {

  /** The servlet request attribute the container puts the verified client chain in. */
  public static final String CLIENT_CERTS = "jakarta.servlet.request.X509Certificate";

  private static final Logger log = LoggerFactory.getLogger(InternalCallerPolicy.class);

  private final Set<String> allowedClients;
  private final Set<String> keyAdminClients;
  private final boolean requireClientCert;
  private final boolean generateEnabled;

  /**
   * @param allowedClients who may call at all
   * @param keyAdminClients who may also generate and rotate keys
   * @param requireClientCert mTLS on: azp must equal the client certificate's CN
   * @param generateEnabled whether {@code POST /internal/v1/keys} is open (dev bootstrap flag)
   */
  public InternalCallerPolicy(
      Set<String> allowedClients, Set<String> keyAdminClients, boolean requireClientCert, boolean generateEnabled) {
    this.allowedClients = Set.copyOf(allowedClients);
    this.keyAdminClients = Set.copyOf(keyAdminClients);
    this.requireClientCert = requireClientCert;
    this.generateEnabled = generateEnabled;
  }

  /** Whether the authenticated client may call this request at all. */
  public boolean allows(Jwt jwt, HttpServletRequest request) {
    String client = jwt.getClaimAsString("azp");
    if (client == null || !allowedClients.contains(client)) {
      return deny(client, "CLIENT_NOT_ALLOWED");
    }
    if (requireClientCert) {
      String cn = clientCommonName(request);
      if (cn == null) {
        return deny(client, "NO_CLIENT_CERT");
      }
      if (!cn.equals(client)) {
        return deny(client, "CERT_MISMATCH");
      }
    }
    return true;
  }

  /**
   * Whether the caller may generate ({@code generate} true) or rotate a key: {@link #allows} plus
   * the key-admin allow-list; generation also needs the dev bootstrap flag.
   */
  public boolean allowsKeyAdmin(Jwt jwt, HttpServletRequest request, boolean generate) {
    if (!allows(jwt, request)) {
      return false;
    }
    String client = jwt.getClaimAsString("azp");
    if (!keyAdminClients.contains(client)) {
      return deny(client, "NOT_KEY_ADMIN");
    }
    if (generate && !generateEnabled) {
      return deny(client, "KEY_GENERATION_DISABLED");
    }
    return true;
  }

  /** The CN of the verified client certificate, or null without one. */
  static String clientCommonName(HttpServletRequest request) {
    if (!(request.getAttribute(CLIENT_CERTS) instanceof X509Certificate[] chain) || chain.length == 0) {
      return null;
    }
    try {
      for (Rdn rdn : new LdapName(chain[0].getSubjectX500Principal().getName()).getRdns()) {
        if ("CN".equalsIgnoreCase(rdn.getType())) {
          return String.valueOf(rdn.getValue());
        }
      }
    } catch (InvalidNameException e) {
      return null;
    }
    return null;
  }

  private static boolean deny(String client, String reason) {
    log.atWarn()
        .addKeyValue("event", "security.internal_call_denied")
        .addKeyValue("clientId", client == null ? "none" : client)
        .addKeyValue("reason", reason)
        .log("Internal API call refused");
    return false;
  }

  /** Token validator: {@code aud} must contain {@code audience}. */
  public static OAuth2TokenValidator<Jwt> audience(String audience) {
    return jwt -> {
      List<String> aud = jwt.getAudience();
      return aud != null && aud.contains(audience)
          ? OAuth2TokenValidatorResult.success()
          : OAuth2TokenValidatorResult.failure(
              new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "token is not for this service", null));
    };
  }
}
