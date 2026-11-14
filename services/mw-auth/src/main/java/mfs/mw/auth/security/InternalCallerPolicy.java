package mfs.mw.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
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
 * The caller rules of {@code /internal/v1/**} (spec 18.2, 18.7, D24; copied from mw-crypto, D14,
 * plus the port rule). A token must name {@code mw-auth} in {@code aud} ({@link #audience}); the
 * request must have come in on the internal port, not the public one; the calling client ({@code
 * azp}) must be on the allow-list and, with mTLS on ({@code mw.tls.enabled}), equal the CN of the
 * client certificate the TLS handshake verified (the JWT stays required: mTLS is in addition). Every
 * refusal logs WARN {@code security.internal_call_denied} with the client and a reason ({@code
 * PUBLIC_PORT}, {@code CLIENT_NOT_ALLOWED}, {@code NO_CLIENT_CERT}, {@code CERT_MISMATCH}).
 */
public final class InternalCallerPolicy {

  /** The servlet request attribute the container puts the verified client chain in. */
  public static final String CLIENT_CERTS = "jakarta.servlet.request.X509Certificate";

  private static final Logger log = LoggerFactory.getLogger(InternalCallerPolicy.class);

  private final Set<String> allowedClients;
  private final boolean requireClientCert;
  private final Predicate<HttpServletRequest> onPublicPort;

  /**
   * @param allowedClients who may call
   * @param requireClientCert mTLS on: azp must equal the client certificate's CN
   * @param onPublicPort whether a request arrived on the public port (refused)
   */
  public InternalCallerPolicy(
      Set<String> allowedClients, boolean requireClientCert, Predicate<HttpServletRequest> onPublicPort) {
    this.allowedClients = Set.copyOf(allowedClients);
    this.requireClientCert = requireClientCert;
    this.onPublicPort = onPublicPort;
  }

  /** Whether the authenticated client may call this request. */
  public boolean allows(Jwt jwt, HttpServletRequest request) {
    String client = jwt.getClaimAsString("azp");
    if (onPublicPort.test(request)) {
      return deny(client, "PUBLIC_PORT");
    }
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
