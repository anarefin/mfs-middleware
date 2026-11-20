package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the signed-in wallet may still pay (ticket 107b, WAL-05). {@code remaining} is what is left
 * before a 1 Tk P2P; a limit the wallet's rule does not set is left out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WalletLimits(boolean allowed, String reasonCode, Remaining remaining) {

  /** Amounts in poisha. A null field is a limit the rule does not set. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Remaining(Amount perTxn, Amount daily, Amount monthly, Long dailyCount, Long monthlyCount) {}
}
