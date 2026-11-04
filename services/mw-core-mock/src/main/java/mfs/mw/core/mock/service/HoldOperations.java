package mfs.mw.core.mock.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * moves the amount to the settlement wallet and the fee to the fee wallet; release only frees the
 * reservation. Called inside the command transaction of {@link CoreCommandService}.
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

  HoldOperations(
      WalletRepository wallets,
      HoldRepository holds,
      LedgerRepository ledger,
      TransferRules rules,
      CoreCommandMapper commands,
      CoreEventMapper events,
      Clock clock) {
    this.wallets = wallets;
    this.holds = holds;
    this.ledger = ledger;
    this.rules = rules;
    this.commands = commands;
    this.events = events;
    this.clock = clock;
  }

  CoreEvent hold(CoreCommand cmd, String coreRef) {
    TransferPayload p = commands.transfer(cmd);
    Optional<String> debitId = wallets.resolveId(p.debitParty().id());
    if (debitId.isEmpty()) {
      return rejected(cmd, coreRef, Reason.WALLET_NOT_FOUND);
    }
    Wallet debit = wallets.lock(debitId.get());
    long amount = p.amount().valueMinor();
    long fee = rules.fee(p.txnType());
    Instant dayStart = clock.instant().truncatedTo(ChronoUnit.DAYS);
    Optional<Reason> refusal = rules.check(p, debit, null, amount, fee, wallets.debitedSince(debit.id(), dayStart));
    if (refusal.isPresent()) {
      return rejected(cmd, coreRef, refusal.get());
    }
    String holdId = "HOLD-" + coreRef;
    holds.insert(holdId, debit.id(), cmd.txnId(), amount, fee);
    wallets.adjustHeld(debit.id(), amount + fee);
    return events.succeeded(cmd, "FundsHeld", coreRef, null, Map.of("holdId", holdId));
  }

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
    if (Hold.HELD.equals(hold.status())) {
      Instant now = clock.instant();
      wallets.lock(hold.walletId());
      ledger.append(cmd.commandId(), hold.txnId(), hold.walletId(), "DEBIT", "TRANSFER", hold.amountMinor(), now);
      ledger.append(cmd.commandId(), hold.txnId(), WalletSeeder.SETTLEMENT_WALLET, "CREDIT", "TRANSFER", hold.amountMinor(), now);
      wallets.adjustBalance(hold.walletId(), -hold.totalMinor());
      wallets.adjustHeld(hold.walletId(), -hold.totalMinor());
      if (hold.feeMinor() > 0) {
        ledger.append(cmd.commandId(), hold.txnId(), hold.walletId(), "DEBIT", "FEE", hold.feeMinor(), now);
        ledger.append(cmd.commandId(), hold.txnId(), WalletSeeder.FEE_WALLET, "CREDIT", "FEE", hold.feeMinor(), now);
        wallets.adjustBalance(WalletSeeder.FEE_WALLET, hold.feeMinor()); // fee wallet before settlement: lock order
      }
      wallets.adjustBalance(WalletSeeder.SETTLEMENT_WALLET, hold.amountMinor());
      holds.setStatus(hold.id(), Hold.COMMITTED);
    }
    // A second commit of a committed hold (a new commandId) is a harmless success.
    return events.succeeded(cmd, "HoldCommitted", coreRef, List.of(balanceOf(hold.walletId())), Map.of("holdId", hold.id()));
  }

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

  private CoreEvent rejected(CoreCommand cmd, String coreRef, Reason reason) {
    return events.rejected(cmd, "HoldRejected", coreRef, reason.name(), reason.message);
  }

  private CoreEvent failed(CoreCommand cmd, String coreRef, Reason reason, String holdId) {
    return events.rejected(cmd, CoreEventMapper.failureType(cmd.commandType()), coreRef, reason.name(), reason.message, Map.of("holdId", holdId));
  }

  private CoreEvent.BalanceAfter balanceOf(String walletId) {
    return new CoreEvent.BalanceAfter(walletId, Amount.bdt(wallets.get(walletId).balanceMinor()));
  }
}
