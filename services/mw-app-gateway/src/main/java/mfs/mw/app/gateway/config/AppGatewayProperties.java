package mfs.mw.app.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
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
 */
@Validated
@ConfigurationProperties("mw.app-gateway")
public record AppGatewayProperties(
    @NotNull Duration replyBudget,
    String instanceId,
    @NotBlank String requestTopic,
    @NotBlank String statusTopic,
    @NotEmpty List<String> supportedTxnTypes,
    @Valid @NotNull Jwt jwt) {

  public AppGatewayProperties {
    instanceId =
        instanceId == null || instanceId.isBlank() ? UUID.randomUUID().toString() : instanceId;
    supportedTxnTypes = List.copyOf(supportedTxnTypes == null ? List.of() : supportedTxnTypes);
  }

  /**
   * @param jwkSetUri the realm's signing keys, fetched lazily on the first token
   * @param realmPath required ending of the token's {@code iss}: the host part depends on the URL
   *     the client used to get the token (Keycloak {@code KC_HOSTNAME_STRICT=false}); the realm
   *     is pinned by the path and by the realm's own keys
   */
  public record Jwt(@NotBlank String jwkSetUri, @NotBlank String realmPath) {}
}
