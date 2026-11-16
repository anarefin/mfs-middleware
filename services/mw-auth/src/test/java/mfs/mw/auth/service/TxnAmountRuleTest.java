package mfs.mw.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import mfs.mw.auth.dto.TxnAuthorisationRequest.Party;
import mfs.mw.auth.dto.TxnAuthorisationRequest.Txn;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The amount rule of the assertion request (ADR D26): at least 1, except BALANCE = 0 with payee = payer. */
class TxnAmountRuleTest {

  static final Party WALLET = new Party("WALLET", "01711000001");

  static Txn txn(String type, long amount, Party payer, Party payee) {
    return new Txn(type, amount, "BDT", payer, payee, "k-1");
  }

  static void assertRefused(Txn t) {
    MwException e = catchThrowableOfType(MwException.class, () -> TxnAmountRule.check(t));
    assertThat(e).isNotNull();
    assertThat(e.code()).isEqualTo(ErrorCodes.VAL_INVALID);
    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void balanceWithZeroAndPayeeEqualToPayerIsAccepted() {
    assertThatCode(() -> TxnAmountRule.check(txn("BALANCE", 0, WALLET, new Party("WALLET", "01711000001"))))
        .doesNotThrowAnyException();
  }

  @Test
  void balanceWithAnAmountIsRefused() {
    assertRefused(txn("BALANCE", 1, WALLET, WALLET));
  }

  @Test
  void balanceWithAnotherPayeeIsRefused() {
    assertRefused(txn("BALANCE", 0, WALLET, new Party("WALLET", "01811000002")));
    assertRefused(txn("BALANCE", 0, WALLET, new Party("MERCHANT", "01711000001")));
  }

  @Test
  void anyOtherTypeNeedsAtLeastOne() {
    assertRefused(txn("P2P", 0, WALLET, new Party("WALLET", "01811000002")));
    assertRefused(txn("P2P", 0, WALLET, WALLET));
    assertThatCode(() -> TxnAmountRule.check(txn("P2P", 1, WALLET, new Party("WALLET", "01811000002"))))
        .doesNotThrowAnyException();
  }
}
