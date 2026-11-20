package mfs.mw.orchestrator.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The caller rules of {@code /internal/v1/**} (spec 18.2, M3 gate decision). A token must name this
 * service in {@code aud} ({@link #audience}); the calling client ({@code azp}, Keycloak's client id
 * of a client-credentials token) must be allowed, and an org-scoped client (mw-portal-api) must
 * always send a non-blank {@code orgId}, so it can only ever read its own org's data. Only a
 * reveal client (mw-admin-api, ticket 36) may send {@code reveal} (any value but {@code false}).
 * A wallet-scoped client (mw-app-gateway, ADR D25) may only search ({@code GET
 * /internal/v1/transactions}, never the detail), always with a non-blank {@code walletId}, and only
 * with the query parameters of {@link #WALLET_PARAMS}: it reads one wallet's history and nothing else.
 * The quote ({@code POST /internal/v1/quotes}, ticket 105: fee, limits and payee before the PIN) is for
 * a wallet-scoped client only, without query parameters (the signed-in wallet is in the body).
 */
public final class InternalCallerPolicy {

  static final String ORG_PARAM = "orgId";
  static final String REVEAL_PARAM = "reveal";
  static final String WALLET_PARAM = "walletId";
  static final String SEARCH_PATH = "/internal/v1/transactions";
  static final String QUOTE_PATH = "/internal/v1/quotes";
  /** The only query parameters a wallet-scoped client may send. */
  static final Set<String> WALLET_PARAMS =
      Set.of(WALLET_PARAM, "direction", "txnType", "status", "from", "to", "limit", "offset");
  private static final Logger log = LoggerFactory.getLogger(InternalCallerPolicy.class);

  private final Set<String> allowedClients;
  private final Set<String> orgScopedClients;
  private final Set<String> revealClients;
  private final Set<String> walletScopedClients;

  public InternalCallerPolicy(
      Set<String> allowedClients, Set<String> orgScopedClients, Set<String> revealClients,
      Set<String> walletScopedClients) {
    this.allowedClients = Set.copyOf(allowedClients);
    this.orgScopedClients = Set.copyOf(orgScopedClients);
    this.revealClients = Set.copyOf(revealClients);
    this.walletScopedClients = Set.copyOf(walletScopedClients);
  }

  /** Whether the authenticated client may make this request. */
  public boolean allows(Jwt jwt, HttpServletRequest request) {
    String client = jwt.getClaimAsString("azp");
    boolean allowed;
    String reason;
    if (client == null || !allowedClients.contains(client)) {
      allowed = false;
      reason = "CLIENT_NOT_ALLOWED";
    } else if (isPath(request, QUOTE_PATH)) {
      allowed = walletScopedClients.contains(client) && "POST".equals(request.getMethod())
          && request.getParameterMap().isEmpty();
      reason = allowed ? null : "QUOTE_NOT_ALLOWED";
    } else if (orgScopedClients.contains(client) && isBlank(request.getParameter(ORG_PARAM))) {
      allowed = false;
      reason = "ORG_REQUIRED";
    } else if (walletScopedClients.contains(client) && !isSearch(request)) {
      allowed = false;
      reason = "PATH_NOT_ALLOWED";
    } else if (walletScopedClients.contains(client) && isBlank(request.getParameter(WALLET_PARAM))) {
      allowed = false;
      reason = "WALLET_REQUIRED";
    } else if (walletScopedClients.contains(client) && !WALLET_PARAMS.containsAll(request.getParameterMap().keySet())) {
      allowed = false;
      reason = "PARAM_NOT_ALLOWED";
    } else if (asksReveal(request) && !revealClients.contains(client)) {
      allowed = false;
      reason = "REVEAL_NOT_ALLOWED";
    } else {
      allowed = true;
      reason = null;
    }
    if (!allowed) {
      log.atWarn()
          .addKeyValue("event", "security.internal_call_denied")
          .addKeyValue("clientId", client == null ? "none" : client)
          .addKeyValue("reason", reason)
          .log("Internal API call refused");
    }
    return allowed;
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

  /** {@code GET /internal/v1/transactions} exactly (the search, not a detail). */
  private static boolean isSearch(HttpServletRequest request) {
    return "GET".equals(request.getMethod()) && isPath(request, SEARCH_PATH);
  }

  private static boolean isPath(HttpServletRequest request, String expected) {
    String uri = request.getRequestURI();
    String context = request.getContextPath();
    String path = uri == null ? "" : context != null && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    return expected.equals(path);
  }

  private static boolean asksReveal(HttpServletRequest request) {
    String v = request.getParameter(REVEAL_PARAM);
    return v != null && !"false".equalsIgnoreCase(v.trim());
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
