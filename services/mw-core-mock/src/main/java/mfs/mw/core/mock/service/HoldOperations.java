package mfs.mw.core.mock.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.core.mock.config.CoreMockProperties;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.dto.HoldPayload;
import mfs.mw.core.mock.dto.TransferPayload;
import mfs.mw.core.mock.entity.Hold;
import mfs.mw.core.mock.entity.Wallet;
import mfs.mw.core.mock.mapper.CoreCommandMapper;
import mfs.mw.core.mock.mapper.CoreEventMapper;
import mfs.mw.core.mock.repository.HoldRepository;
import mfs.mw.core.mock.repository.LedgerRepository;
import mfs.mw.core.mock.repository.WalletRepository;
import org.springframework.stereotype.Component;

/**
 * HoldFunds, CommitHold and ReleaseHold. A hold reserves {@code amount + fee} on the payer's wallet
 * ({@code held_minor}): the ledger balance is unchanged, the available balance shrinks. Commit
 * moves the amount to the settlement wallet (the payload's {@code attributes.settlementWallet}, else
 * SETTLEMENT) and splits the fee into FEE_INCOME, VAT_PAYABLE and COMMISSION_PAYABLE; release only
 * frees the reservation; a hold not committed within {@code mw.coremock.hold-ttl} expires (ticket 94). Called inside the command transaction of {@link CoreCommandService}.
 */
@Component
class HoldOperations {

  private final WalletRepository wallets;
  private final HoldRepository holds;
  private final LedgerRepository ledger;
  private final TransferRules rules;
  private final CoreCommandMapper commands;
  private final CoreEventMapper events;
  private final Clock clock;
  private final java.time.Duration holdTtl;

  HoldOperations(
      WalletRepository wallets,
      HoldRepository holds,
      LedgerRepository ledger,
      TransferRules rules,
      CoreCommandMapper commands,
      CoreEventMapper events,
      Clock clock,
      CoreMockProperties props) {
    this.wallets = wallets;
    this.holds = holds;
    this.ledger = ledger;
    this.rules = rules;
    this.commands = commands;
    this.events = events;
    this.clock = clock;
    this.holdTtl = props.holdTtl();
  }

  CoreEvent hold(CoreCommand cmd, String coreRef) {
    TransferPayload p = commands.transfer(cmd);
    Optional<String> debitId = wallets.resolveId(p.debitParty().id());
    if (debitId.isEmpty()) {
      return rejected(cmd, coreRef, Reason.WALLET_NOT_FOUND);
    }
    Optional<String> settlement = rules.settlementWallet(p.settlementWallet());
    if (settlement.isEmpty()) {
      return rejected(cmd, coreRef, Reason.SETTLEMENT_WALLET_NOT_FOUND);
    }
    Wallet debit = wallets.lock(debitId.get());
    long amount = p.amount().valueMinor();
    Charges charges = rules.charges(p.txnType(), null, debit.type(), amount);
    Optional<Reason> refusal = rules.check(cmd.txnId(), p, debit, null, amount, charges.fee());
    if (refusal.isPresent()) {
      return rejected(cmd, coreRef, refusal.get());
    }
    String holdId = "HOLD-" + coreRef;
    holds.insert(new Hold(holdId, debit.id(), cmd.txnId(), p.txnType(), amount, charges.fee(), charges.vat(),
        charges.commission(), settlement.get(), Hold.HELD, clock.instant().plus(holdTtl)));
    wallets.adjustHeld(debit.id(), amount + charges.fee());
    return events.succeeded(cmd, "FundsHeld", coreRef, null, Map.of("holdId", holdId));
  }

  /**
   * Commit: the amount to the hold's settlement wallet, the fee split into FEE_INCOME, VAT_PAYABLE and
   * COMMISSION_PAYABLE as decided when it was placed (ticket 94, WAL-07), the reservation freed. A
   * released hold fails HOLD_RELEASED, an expired one HOLD_EXPIRED; a committed one is a harmless success.
   */
  CoreEvent commit(CoreCommand cmd, String coreRef) {
    HoldPayload p = commands.hold(cmd);
    Optional<Hold> found = holds.lock(p.holdId());
    if (found.isEmpty()) {
      return failed(cmd, coreRef, Reason.HOLD_NOT_FOUND, p.holdId());
    }
    Hold hold = found.get();
    if (Hold.RELEASED.equals(hold.status())) {
      return failed(cmd, coreRef, Reason.HOLD_RELEASED, hold.id());
    }
    if (Hold.EXPIRED.equals(hold.status())) {
      return failed(cmd, coreRef, Reason.HOLD_EXPIRED, hold.id());
    }
    if (Hold.HELD.equals(hold.status())) {
      wallets.lock(hold.walletId());
      String credit = hold.creditWalletId() == null ? WalletSeeder.SETTLEMENT_WALLET : hold.creditWalletId();
      ledger.post(cmd.commandId(), hold.txnId(), hold.txnType(),
          Legs.payment(hold.walletId(), credit, hold.amountMinor(), hold.feeMinor(), hold.vatMinor(), hold.commissionMinor()),
          clock.instant());
      wallets.adjustHeld(hold.walletId(), -hold.totalMinor());
      holds.setStatus(hold.id(), Hold.COMMITTED);
    }
    // A second commit of a committed hold (a new commandId) is a harmless success.
    return events.succeeded(cmd, "HoldCommitted", coreRef, List.of(balanceOf(hold.walletId())), Map.of("holdId", hold.id()));
  }

  /** Release; an expired hold (already released by the core) is a harmless success too. */
  CoreEvent release(CoreCommand cmd, String coreRef) {
    HoldPayload p = commands.hold(cmd);
    Optional<Hold> found = holds.lock(p.holdId());
    if (found.isEmpty()) {
      return failed(cmd, coreRef, Reason.HOLD_NOT_FOUND, p.holdId());
    }
    Hold hold = found.get();
    if (Hold.COMMITTED.equals(hold.status())) {
      return failed(cmd, coreRef, Reason.HOLD_ALREADY_COMMITTED, hold.id());
    }
    if (Hold.HELD.equals(hold.status())) {
      wallets.lock(hold.walletId());
      wallets.adjustHeld(hold.walletId(), -hold.totalMinor());
      holds.setStatus(hold.id(), Hold.RELEASED);
    }
    return events.succeeded(cmd, "HoldReleased", coreRef, List.of(balanceOf(hold.walletId())), Map.of("holdId", hold.id()));
  }

  /**
   * Releases a hold whose time ran out (ticket 94, FRS 7.3), in the caller's transaction: still HELD and
   * due, its reservation is freed and it turns EXPIRED; returns it, else empty (committed, released or
   * not due: a commit or release got there first).
   */
  Optional<Hold> expire(String holdId) {
    Optional<Hold> found = holds.lock(holdId);
    if (found.isEmpty() || !Hold.HELD.equals(found.get().status()) || found.get().expiresAt().isAfter(clock.instant())) {
      return Optional.empty();
    }
    Hold hold = found.get();
    wallets.lock(hold.walletId());
    wallets.adjustHeld(hold.walletId(), -hold.totalMinor());
    holds.setStatus(hold.id(), Hold.EXPIRED);
    return Optional.of(hold);
  }

  CoreEvent.BalanceAfter balanceAfter(String walletId) {
    return balanceOf(walletId);
  }

  private CoreEvent rejected(CoreCommand cmd, String coreRef, Reason reason) {
    return events.rejected(cmd, "HoldRejected", coreRef, reason.code(), reason.message);
  }

  private CoreEvent failed(CoreCommand cmd, String coreRef, Reason reason, String holdId) {
    return events.rejected(cmd, CoreEventMapper.failureType(cmd.commandType()), coreRef, reason.code(), reason.message, Map.of("holdId", holdId));
  }

  private CoreEvent.BalanceAfter balanceOf(String walletId) {
    return new CoreEvent.BalanceAfter(walletId, Amount.bdt(wallets.get(walletId).balanceMinor()));
  }
}
