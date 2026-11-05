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

  /**
   * The checks on the receiving wallet of money coming from outside the core (ADD_MONEY_BANK: a
   * bank account debited by the partner, ticket 23). The customer authorises it with the wallet's
   * own PIN, so the PIN and its lockout count apply to that wallet; there are no funds or daily
   * debit limits to check, only the per-transaction limit.
   */
  Optional<Reason> checkPayee(TransferPayload p, Wallet credit, long amount) {
    if (credit.locked()) {
      return Optional.of(Reason.ACCOUNT_LOCKED);
    }
    if (!credit.active()) {
      return Optional.of(Reason.CREDIT_ACCOUNT_BLOCKED);
    }
    Optional<Reason> wrongPin = checkPin(p, credit);
    if (wrongPin.isPresent()) {
      return wrongPin;
    }
    if (amount <= 0 || !Amount.BDT.equals(p.amount().currency())) {
      return Optional.of(Reason.INVALID_AMOUNT);
    }
    if (amount > props.limits().perTxnMinor()) {
      return Optional.of(Reason.LIMIT_EXCEEDED_PER_TXN);
    }
    return Optional.empty();
  }

  /** The PIN of {@code wallet}, with the lockout count (spec 9.1). */
  private Optional<Reason> checkPin(TransferPayload p, Wallet wallet) {
    Optional<String> pin = pins.decrypt(p.encryptedPin());
    if (pin.isEmpty()) {
      return Optional.of(Reason.INVALID_PIN_BLOCK);
    }
    if (!encoder.matches(pin.get(), wallet.pinHash())) {
      if (wallets.recordPinFailure(wallet.id(), props.pinMaxFailures())) {
        log.atWarn()
            .addKeyValue("event", "wallet.pin.locked")
            .addKeyValue("walletType", wallet.type())
            .log("wallet locked after too many consecutive wrong PINs");
      }
      return Optional.of(Reason.INVALID_PIN);
    }
    wallets.resetPinFailures(wallet.id());
    return Optional.empty();
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
    // BULK_DISBURSE carries no PIN (maker-checker in the org portal); its wallet types (ORG ->
    // CUSTOMER) are configured, so no other payer passes here without a PIN
    Optional<Reason> wrongPin = p.pinRequired() ? checkPin(p, debit) : Optional.empty();
    if (wrongPin.isPresent()) {
      return wrongPin;
    }
    if (amount <= 0 || !Amount.BDT.equals(p.amount().currency())) {
      return Optional.of(Reason.INVALID_AMOUNT);
    }
    if (amount > props.limits().perTxnMinor()) {
      return Optional.of(Reason.LIMIT_EXCEEDED_PER_TXN);
    }
    if (usedToday + amount > props.limits().dailyMinor(debit.type())) {
      return Optional.of(Reason.LIMIT_EXCEEDED_DAILY);
    }
    if (debit.availableMinor() < amount + fee) {
      return Optional.of(Reason.INSUFFICIENT_BALANCE);
    }
    return Optional.empty();
  }
}
