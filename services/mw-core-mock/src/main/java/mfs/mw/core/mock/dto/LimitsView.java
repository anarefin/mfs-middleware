package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/**
 * WAL-05 answer (contract {@code Limits}): pass or fail, with the reason when it fails and what is
 * left before this amount per transaction, today and this month (UTC), in poisha and in transactions
 * (ticket 94; a limit the wallet's rule does not set is left out).
 */
public record LimitsView(
    @Sensitive(SensitiveType.MSISDN) String walletId,
    boolean allowed,
    @JsonInclude(JsonInclude.Include.NON_NULL) String reasonCode,
    Remaining remaining) {

  /** What the wallet may still pay. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Remaining(Amount perTxn, Amount daily, Amount monthly, Long dailyCount, Long monthlyCount) {}
}
