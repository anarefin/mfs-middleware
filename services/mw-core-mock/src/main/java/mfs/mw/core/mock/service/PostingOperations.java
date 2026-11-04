package mfs.mw.core.mock.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.dto.CreditPayload;
import mfs.mw.core.mock.dto.ReversePayload;
import mfs.mw.core.mock.entity.LedgerEntry;
import mfs.mw.core.mock.entity.Wallet;
import mfs.mw.core.mock.mapper.CoreCommandMapper;
import mfs.mw.core.mock.mapper.CoreEventMapper;
import mfs.mw.core.mock.repository.LedgerRepository;
import mfs.mw.core.mock.repository.WalletRepository;
import org.springframework.stereotype.Component;

/**
 * CreditWallet (inbound money drawn from the settlement wallet, no PIN, no fee) and
 * ReverseTransaction (undo every entry of an earlier transaction, fee included, once). Called
 * inside the command transaction of {@link CoreCommandService}.
 */
@Component
class PostingOperations {

  private final WalletRepository wallets;
  private final LedgerRepository ledger;
  private final CoreCommandMapper commands;
  private final CoreEventMapper events;
  private final Clock clock;

  PostingOperations(
      WalletRepository wallets, LedgerRepository ledger, CoreCommandMapper commands, CoreEventMapper events, Clock clock) {
    this.wallets = wallets;
    this.ledger = ledger;
    this.commands = commands;
    this.events = events;
    this.clock = clock;
  }

  CoreEvent credit(CoreCommand cmd, String coreRef) {
    CreditPayload p = commands.credit(cmd);
    Optional<String> creditId = wallets.resolveId(p.creditParty().id());
    if (creditId.isEmpty()) {
      return rejected(cmd, "CreditRejected", coreRef, Reason.CREDIT_WALLET_NOT_FOUND);
    }
    Wallet credit = wallets.lock(creditId.get());
    if (!credit.canReceive()) {
      return rejected(cmd, "CreditRejected", coreRef, Reason.CREDIT_ACCOUNT_BLOCKED);
    }
    long amount = p.amount().valueMinor();
    if (amount <= 0 || !Amount.BDT.equals(p.amount().currency())) {
      return rejected(cmd, "CreditRejected", coreRef, Reason.INVALID_AMOUNT);
    }
    Instant now = clock.instant();
    ledger.append(cmd.commandId(), cmd.txnId(), WalletSeeder.SETTLEMENT_WALLET, "DEBIT", "TRANSFER", amount, now);
    ledger.append(cmd.commandId(), cmd.txnId(), credit.id(), "CREDIT", "TRANSFER", amount, now);
    wallets.adjustBalance(credit.id(), amount);
    wallets.adjustBalance(WalletSeeder.SETTLEMENT_WALLET, -amount);
    return events.succeeded(
        cmd, "WalletCredited", coreRef, List.of(balanceOf(credit.id())), Map.of());
  }

  CoreEvent reverse(CoreCommand cmd, String coreRef) {
    ReversePayload p = commands.reverse(cmd);
    List<LedgerEntry> original = ledger.entriesOf(p.originalTxnId());
    if (original.isEmpty()) {
      return rejected(cmd, "ReversalRejected", coreRef, Reason.ORIGINAL_NOT_FOUND);
    }
    if (original.stream().anyMatch(e -> "REVERSAL".equals(e.kind()))) {
      return rejected(cmd, "ReversalRejected", coreRef, Reason.ORIGINAL_NOT_REVERSIBLE);
    }
    if (ledger.isReversed(p.originalTxnId())) {
      return rejected(cmd, "ReversalRejected", coreRef, Reason.ALREADY_REVERSED);
    }
    long transferred =
        original.stream().filter(e -> e.debit() && "TRANSFER".equals(e.kind())).mapToLong(LedgerEntry::amountMinor).sum();
    if (p.amount().valueMinor() != transferred || !Amount.BDT.equals(p.amount().currency())) {
      return rejected(cmd, "ReversalRejected", coreRef, Reason.AMOUNT_MISMATCH);
    }

    // What each wallet gets back (+) or gives up (-). Locked in id order, like every multi-wallet change.
    Map<String, Long> delta = new TreeMap<>();
    for (LedgerEntry e : original) {
      delta.merge(e.walletId(), e.debit() ? e.amountMinor() : -e.amountMinor(), Long::sum);
    }
    Map<String, Wallet> locked = new TreeMap<>();
    delta.keySet().forEach(id -> locked.put(id, wallets.lock(id)));
    for (Map.Entry<String, Long> d : delta.entrySet()) {
      if (locked.get(d.getKey()).availableMinor() + d.getValue() < 0) {
        return rejected(cmd, "ReversalRejected", coreRef, Reason.INSUFFICIENT_BALANCE);
      }
    }
    Instant now = clock.instant();
    for (LedgerEntry e : original) {
      ledger.append(cmd.commandId(), cmd.txnId(), e.walletId(), e.debit() ? "CREDIT" : "DEBIT", "REVERSAL", e.amountMinor(), now);
    }
    List<CoreEvent.BalanceAfter> balances = new ArrayList<>();
    for (Map.Entry<String, Long> d : delta.entrySet()) {
      wallets.adjustBalance(d.getKey(), d.getValue());
      if (!"SYSTEM".equals(locked.get(d.getKey()).type())) {
        balances.add(balanceOf(d.getKey()));
      }
    }
    ledger.markReversed(p.originalTxnId(), cmd.txnId());
    return events.succeeded(cmd, "TransactionReversed", coreRef, balances, Map.of());
  }

  private CoreEvent rejected(CoreCommand cmd, String type, String coreRef, Reason reason) {
    return events.rejected(cmd, type, coreRef, reason.name(), reason.message);
  }

  private CoreEvent.BalanceAfter balanceOf(String walletId) {
    return new CoreEvent.BalanceAfter(walletId, Amount.bdt(wallets.get(walletId).balanceMinor()));
  }
}
