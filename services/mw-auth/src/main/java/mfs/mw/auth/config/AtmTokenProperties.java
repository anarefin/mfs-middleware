package mfs.mw.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.auth.atm-token.*}: the ATM cash-out token (ticket 120b2, FRS FL-11).
 *
 * @param ttl validity of a token (10 min)
 * @param maxAttempts checks per token; the wrong guess that uses the last one voids it (as the OTPs)
 * @param keyId the mw-crypto {@code HMAC_ATMTOKEN} key the token's HMAC is made under
 * @param partnerId the ATM partner, the payee of the step-up transaction ({@code PARTNER/<id>})
 * @param issuers clients that may issue a token (mw-app-gateway)
 * @param verifiers clients that may check one (mw-adapter-atm)
 */
@Validated
@ConfigurationProperties("mw.auth.atm-token")
public record AtmTokenProperties(
    @NotNull Duration ttl,
    @Positive int maxAttempts,
    @NotBlank String keyId,
    @NotBlank String partnerId,
    @NotEmpty Set<String> issuers,
    @NotEmpty Set<String> verifiers) {}
