package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/**
 * WAL-05 answer (contract {@code Limits}): pass or fail, with the reason when it fails and what is
 * left per transaction and today (UTC) before this amount. Monthly limits come with ticket 94.
 */
public record LimitsView(
    @Sensitive(SensitiveType.MSISDN) String walletId,
    boolean allowed,
    @JsonInclude(JsonInclude.Include.NON_NULL) String reasonCode,
    Remaining remaining) {

  /** What the wallet may still pay: per transaction and today. */
  public record Remaining(Amount perTxn, Amount daily) {}
}
