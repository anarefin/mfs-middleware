package mfs.mw.auth.dto;

import java.util.List;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/** Answer of {@code POST /internal/v1/seed/pins}: one outcome per wallet, in request order. */
public record SeedPinsResult(List<Outcome> results) {

  /** CREATED, UNCHANGED (already has a PIN), UNKNOWN_WALLET (not projected yet), WEAK. */
  public record Outcome(@Sensitive(SensitiveType.MSISDN) String walletId, String outcome) {}
}
