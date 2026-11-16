package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code POST /app/v1/balance} (ADR D26; contract {@code BalanceRequest} of {@code
 * api/mw-app-gateway/app.v1.openapi.yaml}): the PIN ciphertext for mw-auth and the answer's
 * language. The wallet is never in the body: it is the token's {@code msisdn}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BalanceRequest(
    @NotNull @Valid EncryptedPin encryptedPin, @Pattern(regexp = "bn|en") String locale) {}
