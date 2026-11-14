package mfs.mw.crypto.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mw.crypto.authz}: which internal client (the token's {@code azp}, tied to its client
 * certificate's CN by ticket 66) may run which operation (ticket 63, security review). Deny by default:
 * an operation or purpose without an entry is refused with 403 {@code MW-AUTH-002}.
 *
 * @param pinClients who may call {@code /internal/v1/pin/**} (verify, derive)
 * @param purposes per key purpose ({@code JWT_SIGN}, {@code TOKENISE_MSISDN}, {@code KEK_COLUMN},
 *     {@code HMAC_WEBHOOK}, ...) the clients that may sign, tokenise, encrypt/decrypt or HMAC with its
 *     keys; read by {@code security/CallerPolicy}
 */
@ConfigurationProperties("mw.crypto.authz")
public record OperationAuthzProperties(Set<String> pinClients, Map<String, Set<String>> purposes) {

  public OperationAuthzProperties {
    pinClients = Set.copyOf(pinClients == null ? Set.of() : pinClients);
    Map<String, Set<String>> byPurpose = new LinkedHashMap<>();
    if (purposes != null) {
      // environment overrides arrive lower case: purposes are upper case
      purposes.forEach((k, v) -> byPurpose.put(k.toUpperCase(Locale.ROOT), Set.copyOf(v == null ? Set.of() : v)));
    }
    purposes = Map.copyOf(byPurpose);
  }
}
