package mfs.mw.portal.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.portal-api.*}: bulk file limits (spec 9.4), the Keycloak realm whose tokens are
 * accepted and the realm roles of maker and checker (18.2).
 */
@Validated
@ConfigurationProperties("mw.portal-api")
public record PortalApiProperties(
    @Valid @NotNull Bulk bulk, @Valid @NotNull Jwt jwt, @Valid @NotNull Roles roles) {

  /**
   * @param maxRows most data rows in one file (MVP: 10,000; spec 9.4 says up to 100,000, TBC)
   * @param maxBytes largest file accepted (APISIX caps the route body a little above it)
   * @param orgWallets the core wallet each org pays its bulk batches from (orgId -> wallet id); an
   *     org without one cannot have a batch approved (ticket 34)
   * @param dispatch how child txns are streamed to the orchestrator
   * @param expiry how long a batch may wait for its checker (M3 gate: 72 h)
   * @param reconcile how SUBMITTED rows that never settled are looked up (M3 gate)
   */
  public record Bulk(
      @Positive int maxRows,
      @NotNull DataSize maxBytes,
      Map<String, String> orgWallets,
      @Valid @NotNull Dispatch dispatch,
      @Valid @NotNull Expiry expiry,
      @Valid @NotNull Reconcile reconcile) {

    public Bulk {
      orgWallets = Map.copyOf(orgWallets == null ? Map.of() : orgWallets);
    }
  }

  /**
   * @param enabled runs the dispatcher job (tests switch it off and call the service)
   * @param ratePerSecond most child txns sent per second, over all batches (spec 9.4 "configured rate")
   * @param period how often the dispatcher runs; each run sends what the rate allowed since the last
   */
  public record Dispatch(boolean enabled, @Positive int ratePerSecond, @NotNull Duration period) {}

  /**
   * @param enabled runs the expiry job (tests switch it off and call the service)
   * @param after a PENDING_APPROVAL batch older than this becomes EXPIRED
   * @param period how often the job looks
   * @param batchSize most batches expired per run
   */
  public record Expiry(boolean enabled, @NotNull Duration after, @NotNull Duration period, @Positive int batchSize) {}

  /**
   * @param enabled runs the reconcile job (tests switch it off and call the service)
   * @param after a SUBMITTED row older than this is looked up on the orchestrator's query API
   * @param giveUpAfter a row still without a settled state this long after it was sent is UNRESOLVED
   * @param period how often the job looks
   * @param batchSize most rows looked up per run
   */
  public record Reconcile(
      boolean enabled,
      @NotNull Duration after,
      @NotNull Duration giveUpAfter,
      @NotNull Duration period,
      @Positive int batchSize) {}

  /**
   * @param jwkSetUri the realm's signing keys, fetched lazily on the first token
   * @param realmPath required ending of the token's {@code iss} (the host depends on the URL used)
   */
  public record Jwt(@NotBlank String jwkSetUri, @NotBlank String realmPath) {}

  /** Realm roles: the maker uploads, the checker approves or rejects. */
  public record Roles(@NotBlank String maker, @NotBlank String checker) {}
}
