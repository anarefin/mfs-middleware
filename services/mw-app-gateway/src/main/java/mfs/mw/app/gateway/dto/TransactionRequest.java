package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * Body of {@code POST /app/v1/transactions} (contract {@code
 * api/mw-app-gateway/app.v1.openapi.yaml}). The payer is never in the body: it is the wallet of
 * the token's subject (claim {@code msisdn}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionRequest(
    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,31}") String txnType,
    @NotNull @Valid CounterParty creditParty,
    @NotNull @Valid Amount amount,
    @NotNull @Valid EncryptedPin encryptedPin,
    @Pattern(regexp = "bn|en") String locale,
    @Size(max = 20) Map<@Size(max = 64) String, @Size(max = 256) String> attributes) {

  /** The receiving wallet; P2P only knows wallets addressed by MSISDN. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CounterParty(
      @Pattern(regexp = "WALLET") String type,
      @NotBlank
          @Pattern(regexp = "(\\+?880)?01[3-9][0-9]{8}")
          @Sensitive(SensitiveType.MSISDN)
          String id) {}
}
