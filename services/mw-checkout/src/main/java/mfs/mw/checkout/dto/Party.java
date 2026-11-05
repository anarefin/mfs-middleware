package mfs.mw.checkout.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.checkout.util.Sensitive;
import mfs.mw.checkout.util.SensitiveType;

/** One side of a transaction (contract {@code Party}); {@code id} is a wallet id or MSISDN. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Party(
    String type,
    @Sensitive(SensitiveType.PARTY_ID) String id,
    @Sensitive(SensitiveType.NAME) String name,
    String walletType) {

  public static Party wallet(String msisdn, String walletType) {
    return new Party("WALLET", msisdn, null, walletType);
  }
}
