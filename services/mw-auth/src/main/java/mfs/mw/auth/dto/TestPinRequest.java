package mfs.mw.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import mfs.mw.auth.util.MaskingPolicy;

/** Dev only: {@code POST /test/v1/accounts/{walletId}/pin}, the wallet's new PIN under the transport key. */
public record TestPinRequest(@NotNull @Valid EncryptedPin encryptedPin) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
