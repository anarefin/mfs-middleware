package mfs.mw.core.mock.service;

import java.util.Optional;
import mfs.mw.core.mock.config.CoreMockProperties;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.TransferPayload;
import mfs.mw.core.mock.entity.Wallet;
import org.springframework.stereotype.Component;

/**
 * The checks every money-moving request on a payer passes, in the core's order: wallet types of
 * the txnType, status, authorisation, amount, limits, funds (available = balance minus held). Used
 * by validate, post and hold. The customer's authorisation is mw-auth's transaction assertion
 * ({@link AssertionCheck}, ticket 71); the core holds and checks no PIN and keeps no PIN lockout
 * (ticket 72: mw-auth owns both). A bulk child needs none (maker-checker in the org portal).
 */
@Component
class TransferRules {

  private final CoreMockProperties props;
  private final AssertionCheck assertions;

  TransferRules(CoreMockProperties props, AssertionCheck assertions) {
    this.props = props;
    this.assertions = assertions;
  }

  long fee(String txnType) {
    return props.fees().getOrDefault(txnType, 0L);
  }

  /**
   * The checks on the receiving wallet of money coming from outside the core (ADD_MONEY_BANK: a
   * bank account debited by the partner, ticket 23). The customer authorises it as that wallet
   * (the assertion's subject is the receiving wallet); there are no funds or daily debit limits to
   * check, only the per-transaction limit.
   */
  Optional<Reason> checkPayee(String txnId, TransferPayload p, Wallet credit, long amount) {
    if (credit.locked()) {
      return Optional.of(Reason.ACCOUNT_LOCKED);
    }
    if (!credit.active()) {
      return Optional.of(Reason.CREDIT_ACCOUNT_BLOCKED);
    }
    Optional<Reason> refused = assertions.check(p, credit, txnId);
    if (refused.isPresent()) {
      return refused;
    }
    if (amount <= 0 || !Amount.BDT.equals(p.amount().currency())) {
      return Optional.of(Reason.INVALID_AMOUNT);
    }
    if (amount > props.limits().perTxnMinor()) {
      return Optional.of(Reason.LIMIT_EXCEEDED_PER_TXN);
    }
    return Optional.empty();
  }

  /**
   * @param txnId the command's transaction (an assertion's jti is bound to it)
   * @param credit the receiving wallet, or {@code null} when it is not a wallet of this core (hold) */
  Optional<Reason> check(
      String txnId, TransferPayload p, Wallet debit, Wallet credit, long amount, long fee, long usedToday) {
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
    // BULK_DISBURSE carries no assertion (maker-checker in the org portal); its wallet types (ORG ->
    // CUSTOMER) are configured, so no other payer passes here without one
    Optional<Reason> refused = p.authorisationRequired() ? assertions.check(p, debit, txnId) : Optional.empty();
    if (refused.isPresent()) {
      return refused;
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
