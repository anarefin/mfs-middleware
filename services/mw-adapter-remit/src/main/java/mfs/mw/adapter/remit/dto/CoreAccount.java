package mfs.mw.adapter.remit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import mfs.mw.adapter.remit.util.Sensitive;
import mfs.mw.adapter.remit.util.SensitiveType;

/**
 * The core's WAL-01 account (contract {@code core-wallet.v1} {@code Account}, ticket 119c): what the
 * beneficiary lookup needs. The holder name never leaves this service unmasked.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoreAccount(
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String type,
    String status,
    String kycLevel,
    @Sensitive(SensitiveType.NAME) String holderName) {

  @Override
  public String toString() {
    return "CoreAccount[type=" + type + ", status=" + status + ", kycLevel=" + kycLevel + "]";
  }
}
