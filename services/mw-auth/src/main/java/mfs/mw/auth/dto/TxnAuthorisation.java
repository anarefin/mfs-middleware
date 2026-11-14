package mfs.mw.auth.dto;

import java.time.Instant;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * Answer of {@code POST /internal/v1/txn-authorisations} (contract {@code auth-internal.v1}
 * TxnAuthorisation): the compact JWS the channel puts into {@code mw.txn.request.v1}, when it
 * expires and whether it says {@code newDevice}.
 */
public record TxnAuthorisation(
    @Sensitive(SensitiveType.SECRET) String assertion, Instant expiresAt, boolean newDevice) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
