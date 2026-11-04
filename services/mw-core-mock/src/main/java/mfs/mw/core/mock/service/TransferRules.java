package mfs.mw.core.mock.service;

import java.util.Optional;
import mfs.mw.core.mock.config.CoreMockProperties;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.TransferPayload;
import mfs.mw.core.mock.entity.Wallet;
import mfs.mw.core.mock.repository.WalletRepository;
import mfs.mw.core.mock.security.PinCipher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The checks every money-moving request on a payer passes, in the core's order: wallet types of
 * the txnType, status, PIN, amount, limits, funds (available = balance minus held). Used by
 * validate, post and hold. The PIN check keeps the lockout count (spec 9.1): a wrong PIN counts
 * one failure and the {@code pinMaxFailures}-th in a row locks the wallet; a correct PIN resets
 * the count. It runs inside the command transaction, so a replayed command (answered from the
 * command log) never counts twice.
 */
@Component
class TransferRules {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TransferRules.class);

  private final CoreMockProperties props;
  private final PinCipher pins;
  private final PasswordEncoder encoder;
  private final WalletRepository wallets;

  TransferRules(CoreMockProperties props, PinCipher pins, PasswordEncoder encoder, WalletRepository wallets) {
    this.props = props;
    this.pins = pins;
    this.encoder = encoder;
    this.wallets = wallets;
  }

  long fee(String txnType) {
    return props.fees().getOrDefault(txnType, 0L);
  }

  /** @param credit the receiving wallet, or {@code null} when it is not a wallet of this core (hold) */
  Optional<Reason> check(
      TransferPayload p, Wallet debit, Wallet credit, long amount, long fee, long usedToday) {
    CoreMockProperties.WalletTypes types = props.walletTypes().get(p.txnType());
    if (types != null
        && (!types.debit().equals(debit.type()) || (credit != null && !types.credit().equals(credit.type())))) {
      return Optional.of(Reason.INVALID_COUNTERPARTY);
    }
    if (debit.locked()) {
      return Optional.of(Reason.ACCOUNT_LOCKED);
    }
    if (!debit.active()) {
      return Optional.of(Reason.ACCOUNT_BLOCKED);
    }
    if (credit != null && !credit.canReceive()) {
      return Optional.of(Reason.CREDIT_ACCOUNT_BLOCKED);
    }
    Optional<String> pin = pins.decrypt(p.encryptedPin());
    if (pin.isEmpty()) {
      return Optional.of(Reason.INVALID_PIN_BLOCK);
    }
    if (!encoder.matches(pin.get(), debit.pinHash())) {
      if (wallets.recordPinFailure(debit.id(), props.pinMaxFailures())) {
        log.atWarn()
            .addKeyValue("event", "wallet.pin.locked")
            .addKeyValue("walletType", debit.type())
            .log("wallet locked after too many consecutive wrong PINs");
      }
      return Optional.of(Reason.INVALID_PIN);
    }
    wallets.resetPinFailures(debit.id());
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
