package mfs.mw.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.auth.assertion.*}: the transaction authorisation assertion (spec 18.7, D22; ticket 71).
 *
 * @param issuer {@code iss}, {@code mw-auth}
 * @param audience {@code aud}, {@code core}
 * @param ttl the life ({@code exp - iat}), 60 s; a repeat with the same idempotency key inside it
 *     gets the same assertion
 * @param newDeviceWindow a wallet whose device was bound less than this ago gets {@code newDevice:
 *     true} (24 h)
 * @param callers the services token clients ({@code azp}) that may ask: the channels
 */
@Validated
@ConfigurationProperties("mw.auth.assertion")
public record AssertionProperties(
    @NotBlank String issuer,
    @NotBlank String audience,
    @NotNull Duration ttl,
    @NotNull Duration newDeviceWindow,
    @NotEmpty Set<String> callers) {

  public AssertionProperties {
    callers = Set.copyOf(callers == null ? Set.of() : callers);
  }
}
