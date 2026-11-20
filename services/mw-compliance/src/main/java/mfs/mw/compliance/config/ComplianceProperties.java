package mfs.mw.compliance.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import mfs.mw.compliance.dto.DocumentType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mw.compliance} (ticket 124): the eKYC match rules and document limits, the EC NID service and mw-crypto.
 * Credentials ({@code ec.password}, {@code crypto.client-secret}) are empty here and come from Vault, so moving
 * from the EC mock to the real service is configuration only.
 */
@ConfigurationProperties("mw.compliance")
public record ComplianceProperties(Kyc kyc, Ec ec, Crypto crypto, Core core, Set<String> unscopedClients) {

  /**
   * {@code unscoped-clients}: service clients ({@code azp}) that may read any application (mw-admin-api, the
   * console). Every other client must name the authenticated end user ({@code X-Mw-Subject-Type/-Id}).
   */
  public ComplianceProperties {
    unscopedClients = Set.copyOf(unscopedClients == null ? Set.of() : unscopedClients);
  }

  /**
   * @param nameThreshold normalised name similarity (0..1) at or above which an exact-DOB match is FULL; below it
   *     the application is held for manual review
   * @param maxDocumentBytes largest image accepted (decoded)
   * @param contentTypes image types accepted
   * @param requiredDocuments the images an application needs before submit
   */
  public record Kyc(
      double nameThreshold, int maxDocumentBytes, Set<String> contentTypes, List<DocumentType> requiredDocuments) {}

  /**
   * The EC NID service (FRS EXT-07). Verify is the one call, idempotent: retried (Resilience4j {@code ec-nid})
   * on a connection failure or 5xx only, never on a timeout; circuit breaker {@code ec-nid}.
   */
  public record Ec(URI baseUrl, String username, String password, Duration connectTimeout, Duration timeout) {}

  /**
   * The core wallet REST API (ADR D31; mw-core-mock in the stack): {@code PUT /core/v1/accounts/{walletId}/kyc-level}
   * with this service's realm token ({@code crypto.token-uri}, audience mapper {@code aud-mw-core-mock}).
   */
  public record Core(URI baseUrl, Duration connectTimeout, Duration timeout) {}

  /** mw-crypto (mTLS) and this service's realm {@code services} client for it (also the core's token). */
  public record Crypto(
      URI baseUrl, URI tokenUri, String clientId, String clientSecret, Duration connectTimeout, Duration timeout) {}

  /** The defaults of application.yml, for unit tests. */
  public static ComplianceProperties defaults() {
    return new ComplianceProperties(
        new Kyc(0.85, 2 * 1024 * 1024, Set.of("image/jpeg", "image/png"),
            List.of(DocumentType.NID_FRONT, DocumentType.NID_BACK, DocumentType.SELFIE)),
        new Ec(URI.create("http://mw-simulators:8080"), "mw-compliance", "", Duration.ofSeconds(2), Duration.ofSeconds(5)),
        new Crypto(URI.create("https://mw-crypto:8080"), URI.create("http://keycloak:8080/realms/services/protocol/openid-connect/token"),
            "mw-compliance", "", Duration.ofSeconds(2), Duration.ofSeconds(2)),
        new Core(URI.create("http://mw-core-mock:8080"), Duration.ofSeconds(2), Duration.ofSeconds(5)),
        Set.of("mw-admin-api"));
  }
}
