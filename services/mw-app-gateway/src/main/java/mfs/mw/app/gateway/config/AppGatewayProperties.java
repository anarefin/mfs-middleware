package mfs.mw.app.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.app-gateway.*}: the synchronous wait (spec 7.5), this instance's reply address, the
 * topics and the Keycloak realm whose tokens are accepted (9.1, 18.2).
 *
 * @param replyBudget how long a request waits for a terminal status before answering 202
 * @param instanceId value of the {@code mw-reply-to} header and suffix of the reply consumer
 *     group; blank means a random id per start
 * @param supportedTxnTypes the {@code txnType}s the app may submit (P2P only so far)
 * @param allowedAttributes the {@code attributes} keys the app may send; any other key is
 *     refused, so a client can never set keys the middleware reserves (e.g. {@code duplicate},
 *     {@code requestTxnId})
 * @param txnTypeRoles per {@code txnType}, the wallet types (from the token's realm role:
 *     {@code CUSTOMER}, {@code AGENT}) that may initiate it (spec WP20); a type not listed is refused
 *     for every role (deny by default)
 */
@Validated
@ConfigurationProperties("mw.app-gateway")
public record AppGatewayProperties(
    @NotNull Duration replyBudget,
    String instanceId,
    @NotBlank String requestTopic,
    @NotBlank String statusTopic,
    @NotEmpty List<String> supportedTxnTypes,
    List<String> allowedAttributes,
    @Valid @NotNull Jwt jwt,
    Map<String, List<String>> txnTypeRoles) {

  public AppGatewayProperties {
    instanceId =
        instanceId == null || instanceId.isBlank() ? UUID.randomUUID().toString() : instanceId;
    supportedTxnTypes = List.copyOf(supportedTxnTypes == null ? List.of() : supportedTxnTypes);
    allowedAttributes = List.copyOf(allowedAttributes == null ? List.of() : allowedAttributes);
    txnTypeRoles = Map.copyOf(txnTypeRoles == null ? Map.of() : txnTypeRoles);
  }

  /** True when a user of this wallet type may initiate this transaction type (deny by default). */
  public boolean mayInitiate(String walletType, String txnType) {
    return txnTypeRoles.getOrDefault(txnType, List.of()).contains(walletType);
  }

  /**
   * @param jwkSetUri the realm's signing keys, fetched lazily on the first token
   * @param realmPath required ending of the token's {@code iss}: the host part depends on the URL
   *     the client used to get the token (Keycloak {@code KC_HOSTNAME_STRICT=false}); the realm
   *     is pinned by the path and by the realm's own keys
   */
  public record Jwt(@NotBlank String jwkSetUri, @NotBlank String realmPath) {}
}
