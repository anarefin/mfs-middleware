package mfs.mw.core.mock.service;

import java.util.Optional;
import mfs.mw.core.mock.config.CoreMockProperties;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.TransferPayload;
import mfs.mw.core.mock.entity.Wallet;
import mfs.mw.core.mock.security.PinCipher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The checks every money-moving request on a payer passes, in the core's order: status, PIN,
 * amount, limits, funds (available = balance minus held). Used by validate, post and hold.
 */
@Component
class TransferRules {

  private final CoreMockProperties props;
  private final PinCipher pins;
  private final PasswordEncoder encoder;

  TransferRules(CoreMockProperties props, PinCipher pins, PasswordEncoder encoder) {
    this.props = props;
    this.pins = pins;
    this.encoder = encoder;
  }

  long fee(String txnType) {
    return props.fees().getOrDefault(txnType, 0L);
  }

  /** @param credit the receiving wallet, or {@code null} when it is not a wallet of this core (hold) */
  Optional<Reason> check(
      TransferPayload p, Wallet debit, Wallet credit, long amount, long fee, long usedToday) {
    if (!debit.active()) {
      return Optional.of(Reason.ACCOUNT_BLOCKED);
    }
    if (credit != null && !credit.active()) {
      return Optional.of(Reason.CREDIT_ACCOUNT_BLOCKED);
    }
    Optional<String> pin = pins.decrypt(p.encryptedPin());
    if (pin.isEmpty()) {
      return Optional.of(Reason.INVALID_PIN_BLOCK);
    }
    if (!encoder.matches(pin.get(), debit.pinHash())) {
      return Optional.of(Reason.INVALID_PIN);
    }
    if (amount <= 0 || !Amount.BDT.equals(p.amount().currency())) {
      return Optional.of(Reason.INVALID_AMOUNT);
    }
    if (amount > props.limits().perTxnMinor()) {
      return Optional.of(Reason.LIMIT_EXCEEDED_PER_TXN);
    }
    if (usedToday + amount > props.limits().dailyMinor()) {
      return Optional.of(Reason.LIMIT_EXCEEDED_DAILY);
    }
    if (debit.availableMinor() < amount + fee) {
      return Optional.of(Reason.INSUFFICIENT_BALANCE);
    }
    return Optional.empty();
  }
}
