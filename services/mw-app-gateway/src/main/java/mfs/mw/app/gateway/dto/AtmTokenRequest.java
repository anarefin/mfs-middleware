package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /app/v1/atm/tokens} (ticket 120b2; contract {@code AtmTokenRequest} of {@code
 * api/mw-app-gateway/app.v1.openapi.yaml}): the cash-out amount the token is bound to and the PIN
 * ciphertext for mw-auth's step-up. The wallet is the token's {@code msisdn}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AtmTokenRequest(@NotNull @Valid Amount amount, @NotNull @Valid EncryptedPin encryptedPin) {}
