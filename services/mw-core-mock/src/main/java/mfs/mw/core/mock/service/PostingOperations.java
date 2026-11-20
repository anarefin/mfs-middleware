package mfs.mw.core.mock.service;

import java.time.Clock;
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
 * CreditWallet (inbound money drawn from a settlement wallet, the one its attributes name or
 * SETTLEMENT; no PIN, no fee) and
 * ReverseTransaction (undo every entry of an earlier transaction, fee included, once). Called
 * inside the command transaction of {@link CoreCommandService}.
 */
@Component
class PostingOperations {

  private final WalletRepository wallets;
  private final LedgerRepository ledger;
  private final CoreCommandMapper commands;
  private final CoreEventMapper events;
  private final TransferRules rules;
  private final Clock clock;

  PostingOperations(WalletRepository wallets, LedgerRepository ledger, CoreCommandMapper commands, CoreEventMapper events,
      TransferRules rules, Clock clock) {
    this.rules = rules;
    this.wallets = wallets;
    this.ledger = ledger;
    this.commands = commands;
    this.events = events;
    this.clock = clock;
  }

  CoreEvent credit(CoreCommand cmd, String coreRef) {
    CreditPayload p = commands.credit(cmd);
    // A txn is credited once whatever the commandId (an operator's fresh round, ticket 37):
    // a reversed one is refused, an already credited one answered as credited without effect.
    if (ledger.isReversed(cmd.txnId())) {
      return rejected(cmd, "CreditRejected", coreRef, Reason.ALREADY_REVERSED);
    }
    Optional<String> already = ledger.entriesOf(cmd.txnId()).stream()
        .filter(e -> !e.debit() && "TRANSFER".equals(e.kind())).map(LedgerEntry::walletId).findFirst();
    if (already.isPresent()) {
      return events.succeeded(cmd, "WalletCredited", coreRef, List.of(balanceOf(already.get())), Map.of());
    }
    Optional<String> creditId = wallets.resolveId(p.creditParty().id());
    if (creditId.isEmpty()) {
      return rejected(cmd, "CreditRejected", coreRef, Reason.CREDIT_WALLET_NOT_FOUND);
    }
    Optional<String> settlement = rules.settlementWallet(p.settlementWallet());
    if (settlement.isEmpty()) {
      return rejected(cmd, "CreditRejected", coreRef, Reason.SETTLEMENT_WALLET_NOT_FOUND);
    }
    Wallet credit = wallets.lock(creditId.get());
    if (!credit.canReceive()) {
      return rejected(cmd, "CreditRejected", coreRef, Reason.CREDIT_ACCOUNT_BLOCKED);
    }
    long amount = p.amount().valueMinor();
    if (amount <= 0 || !Amount.BDT.equals(p.amount().currency())) {
      return rejected(cmd, "CreditRejected", coreRef, Reason.INVALID_AMOUNT);
    }
    ledger.post(cmd.commandId(), cmd.txnId(), p.txnType(), List.of(
        new LedgerEntry(settlement.get(), "DEBIT", "TRANSFER", amount),
        new LedgerEntry(credit.id(), "CREDIT", "TRANSFER", amount)), clock.instant());
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
    if (!Amount.BDT.equals(p.amount().currency())) {
      return rejected(cmd, "ReversalRejected", coreRef, Reason.AMOUNT_MISMATCH);
    }
    long refunded = ledger.refundedMinor(p.originalTxnId());
    if (p.amount().valueMinor() <= 0) {
      return rejected(cmd, "ReversalRejected", coreRef, Reason.INVALID_AMOUNT);
    }
    if (p.amount().valueMinor() > transferred - refunded) {
      return rejected(cmd, "ReversalRejected", coreRef, Reason.REFUND_EXCEEDS);
    }
    if (p.amount().valueMinor() < transferred || refunded > 0) {
      return partial(cmd, coreRef, p, original, transferred, refunded);
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
    ledger.post(cmd.commandId(), cmd.txnId(), "REVERSAL", original.stream()
        .map(e -> new LedgerEntry(e.walletId(), e.debit() ? "CREDIT" : "DEBIT", "REVERSAL", e.amountMinor())).toList(),
        clock.instant());
    List<CoreEvent.BalanceAfter> balances = new ArrayList<>();
    for (Map.Entry<String, Long> d : delta.entrySet()) {
      if (!"SYSTEM".equals(locked.get(d.getKey()).type())) {
        balances.add(balanceOf(d.getKey()));
      }
    }
    ledger.markReversed(p.originalTxnId(), cmd.txnId());
    return events.succeeded(cmd, "TransactionReversed", coreRef, balances, Map.of());
  }

  /**
   * A partial reversal (a refund, ticket 116): {@code amount} back from the original's credited wallet to
   * its debited wallet under the refund's own txnId; the fee stays charged. The refunded total is kept
   * per original; when it reaches the transfer amount the original counts as reversed.
   */
  private CoreEvent partial(CoreCommand cmd, String coreRef, ReversePayload p, List<LedgerEntry> original,
      long transferred, long refunded) {
    List<LedgerEntry> transfer = original.stream().filter(e -> "TRANSFER".equals(e.kind())).toList();
    List<String> payers = transfer.stream().filter(LedgerEntry::debit).map(LedgerEntry::walletId).distinct().toList();
    List<String> payees = transfer.stream().filter(e -> !e.debit()).map(LedgerEntry::walletId).distinct().toList();
    if (payers.size() != 1 || payees.size() != 1) {
      return rejected(cmd, "ReversalRejected", coreRef, Reason.ORIGINAL_NOT_REVERSIBLE);
    }
    String payer = payers.get(0);
    String payee = payees.get(0);
    long amount = p.amount().valueMinor();
    Map<String, Wallet> locked = new TreeMap<>();
    new TreeMap<>(Map.of(payer, 0, payee, 0)).keySet().forEach(id -> locked.put(id, wallets.lock(id)));
    if (locked.get(payee).availableMinor() < amount) {
      return rejected(cmd, "ReversalRejected", coreRef, Reason.INSUFFICIENT_BALANCE);
    }
    ledger.post(cmd.commandId(), cmd.txnId(), "REVERSAL", List.of(
        new LedgerEntry(payee, "DEBIT", "REVERSAL", amount),
        new LedgerEntry(payer, "CREDIT", "REVERSAL", amount)), clock.instant());
    ledger.recordPartialReversal(p.originalTxnId(), cmd.txnId(), amount);
    if (refunded + amount == transferred) {
      ledger.markReversed(p.originalTxnId(), cmd.txnId());
    }
    List<CoreEvent.BalanceAfter> balances = new ArrayList<>();
    for (Wallet w : locked.values()) {
      if (!"SYSTEM".equals(w.type())) {
        balances.add(balanceOf(w.id()));
      }
    }
    return events.succeeded(cmd, "TransactionReversed", coreRef, balances, Map.of());
  }

  private CoreEvent rejected(CoreCommand cmd, String type, String coreRef, Reason reason) {
    return events.rejected(cmd, type, coreRef, reason.code(), reason.message);
  }

  private CoreEvent.BalanceAfter balanceOf(String walletId) {
    return new CoreEvent.BalanceAfter(walletId, Amount.bdt(wallets.get(walletId).balanceMinor()));
  }
}
