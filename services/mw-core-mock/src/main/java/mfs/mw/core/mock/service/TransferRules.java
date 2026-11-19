package mfs.mw.core.mock.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import mfs.mw.core.mock.config.CoreMockProperties;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.TransferPayload;
import mfs.mw.core.mock.entity.LimitRule;
import mfs.mw.core.mock.entity.Usage;
import mfs.mw.core.mock.entity.Wallet;
import mfs.mw.core.mock.repository.SheetRepository;
import mfs.mw.core.mock.repository.WalletRepository;
import org.springframework.stereotype.Component;

/**
 * The checks every money-moving request on a payer passes, in the core's order: wallet types of
 * the txnType, status, authorisation, amount, limits, funds (available = balance minus held). Used
 * by validate, post and hold. The customer's authorisation is mw-auth's transaction assertion
 * ({@link AssertionCheck}, ticket 71); the core holds and checks no PIN and keeps no PIN lockout
 * (ticket 72: mw-auth owns both). A bulk child needs none (maker-checker in the org portal). The
 * charges come from the tariff sheet and the limits from the limit sheet (ticket 94, {@link Tariff},
 * {@link Limits}): the payer's rule by its wallet type and KYC level, usage per UTC day and month.
 */
@Component
class TransferRules {

  private final CoreMockProperties props;
  private final AssertionCheck assertions;
  private final SheetRepository sheets;
  private final WalletRepository wallets;
  private final Clock clock;

  TransferRules(CoreMockProperties props, AssertionCheck assertions, SheetRepository sheets, WalletRepository wallets,
      Clock clock) {
    this.props = props;
    this.assertions = assertions;
    this.sheets = sheets;
    this.wallets = wallets;
    this.clock = clock;
  }

  /** The charges of a transaction (the commands carry no channel: only {@code *} channel rows apply to them). */
  Charges charges(String txnType, String channel, String customerType, long amount) {
    return Tariff.charges(sheets.tariff(txnType), txnType, channel, customerType, amount);
  }

  /**
   * The settlement wallet a hold's commit credits or a credit draws on (ticket 94, WAL-07, WAL-10):
   * {@code SETTLEMENT} when none is named, else the named one, which must be a SYSTEM wallet whose id
   * starts with {@code SETTLEMENT}; empty when it is not.
   */
  Optional<String> settlementWallet(String named) {
    if (named == null || named.isBlank()) {
      return Optional.of(WalletSeeder.SETTLEMENT_WALLET);
    }
    if (!named.startsWith(WalletSeeder.SETTLEMENT_PREFIX)) {
      return Optional.empty();
    }
    return wallets.resolveId(named).filter(id -> id.equals(named) && "SYSTEM".equals(wallets.get(id).type()));
  }

  /** The limit rule of a payer for {@code txnType}; {@link LimitRule#NONE} when none matches. */
  LimitRule rule(String txnType, Wallet payer) {
    return Limits.rule(sheets.limits(txnType), txnType, payer.type(), payer.kycLevel()).orElse(LimitRule.NONE);
  }

  /** The payer's limits for {@code amount} now: refusal and what is left. */
  Limits.Result limits(String txnType, Wallet payer, long amount) {
    LimitRule rule = rule(txnType, payer);
    Instant now = clock.instant();
    Instant day = now.truncatedTo(ChronoUnit.DAYS);
    Instant month = now.atZone(ZoneOffset.UTC).with(TemporalAdjusters.firstDayOfMonth()).truncatedTo(ChronoUnit.DAYS).toInstant();
    Usage used = wallets.usage(payer.id(), rule.perType() ? txnType : null, day, month);
    return Limits.check(rule, amount, used);
  }

  /** The limits left after this amount as TransactionValidated carries them (amounts only). */
  static Map<String, Amount> left(Limits.Result r, long amount) {
    Map<String, Amount> left = new LinkedHashMap<>();
    if (r.perTxnMax() != null) {
      left.put("PER_TXN", Amount.bdt(r.perTxnMax()));
    }
    if (r.dailyLeft() != null) {
      left.put("DAILY", Amount.bdt(Math.max(0, r.dailyLeft() - amount)));
    }
    if (r.monthlyLeft() != null) {
      left.put("MONTHLY", Amount.bdt(Math.max(0, r.monthlyLeft() - amount)));
    }
    return left;
  }

  /**
   * The checks on the receiving wallet of money coming from outside the core (ADD_MONEY_BANK: a
   * bank account debited by the partner, ticket 23). The customer authorises it as that wallet
   * (the assertion's subject is the receiving wallet); there are no funds or daily debit limits to
   * check, only the per-transaction limits of its rule.
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
    LimitRule rule = rule(p.txnType(), credit);
    if (rule.perTxnMin() != null && amount < rule.perTxnMin()) {
      return Optional.of(Reason.LIMIT_BELOW_MIN_PER_TXN);
    }
    if (rule.perTxnMax() != null && amount > rule.perTxnMax()) {
      return Optional.of(Reason.LIMIT_EXCEEDED_PER_TXN);
    }
    return Optional.empty();
  }

  /** Why a payer in this status may not pay, if it may not (ticket 94: DORMANT, CLOSED). */
  static Optional<Reason> payerStatus(Wallet debit) {
    if (debit.locked()) {
      return Optional.of(Reason.ACCOUNT_LOCKED);
    }
    if (debit.dormant()) {
      return Optional.of(Reason.ACCOUNT_DORMANT);
    }
    if (debit.closed()) {
      return Optional.of(Reason.ACCOUNT_CLOSED);
    }
    if (!debit.active()) {
      return Optional.of(Reason.ACCOUNT_BLOCKED);
    }
    return Optional.empty();
  }

  /**
   * @param txnId the command's transaction (an assertion's jti is bound to it)
   * @param credit the receiving wallet, or {@code null} when it is not a wallet of this core (hold)
   * @param fee what the payer pays on top of the amount (VAT included)
   */
  Optional<Reason> check(String txnId, TransferPayload p, Wallet debit, Wallet credit, long amount, long fee) {
    CoreMockProperties.WalletTypes types = props.walletTypes().get(p.txnType());
    if (types != null
        && (!types.debit().equals(debit.type()) || (credit != null && !types.credit().equals(credit.type())))) {
      return Optional.of(Reason.INVALID_COUNTERPARTY);
    }
    Optional<Reason> status = payerStatus(debit);
    if (status.isPresent()) {
      return status;
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
    Optional<Reason> limit = limits(p.txnType(), debit, amount).reason();
    if (limit.isPresent()) {
      return limit;
    }
    if (debit.availableMinor() < amount + fee) {
      return Optional.of(Reason.INSUFFICIENT_BALANCE);
    }
    return Optional.empty();
  }
}
