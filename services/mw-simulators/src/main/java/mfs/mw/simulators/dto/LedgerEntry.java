package mfs.mw.simulators.dto;

import java.time.Instant;

/**
 * One ledger entry of a wallet as mw-core-mock's {@code GET /core/v1/statements} answers it (contract {@code
 * api/mw-core-mock/core-wallet.v1}, schema {@code Entry}; ticket 94), the input of the trust bank statements.
 *
 * @param direction DEBIT or CREDIT
 * @param amountMinor poisha
 * @param balanceAfterMinor the wallet's balance right after the entry, null on entries older than ticket 94
 */
public record LedgerEntry(
    String txnId, String direction, String kind, long amountMinor, Long balanceAfterMinor, Instant at) {

  public boolean credit() {
    return "CREDIT".equals(direction);
  }
}
