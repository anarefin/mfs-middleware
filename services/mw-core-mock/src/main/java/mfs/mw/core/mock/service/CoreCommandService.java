package mfs.mw.core.mock.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mfs.mw.core.mock.config.CoreMockProperties;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.dto.Fault;
import mfs.mw.core.mock.dto.TransferPayload;
import mfs.mw.core.mock.entity.Wallet;
import mfs.mw.core.mock.mapper.CoreCommandMapper;
import mfs.mw.core.mock.mapper.CoreEventMapper;
import mfs.mw.core.mock.messaging.EventMetadata;
import mfs.mw.core.mock.messaging.OutboxWriter;
import mfs.mw.core.mock.repository.CommandLogRepository;
import mfs.mw.core.mock.repository.LedgerRepository;
import mfs.mw.core.mock.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core (spec 6.3): validate, post, hold, commit, release, credit, reverse and query. Every
 * command is idempotent on {@code commandId}: the first result event is stored verbatim in {@code
 * command_log}, in the same transaction as the ledger change and the outbox row, and a repeated
 * command is answered with that same event again. Injected faults ({@link FaultRegistry}) change
 * how the reply travels (delayed, dropped, duplicated) or refuse the command; the ledger and the
 * command log stay the truth, so QueryTransaction can tell what really happened.
 */
@Service
public class CoreCommandService {

  public static final String EVENT_TOPIC = "core.wallet.event.v1";
  public static final String PAYMENT_EVENT_TOPIC = "core.payment.event.v1";
  static final String VALIDATE = "ValidateTransaction";
  static final String POST = "PostTransfer";
  /** Party types that are never a wallet of this core: paid outside it by a partner. */
  static final Set<String> EXTERNAL_CREDIT_TYPES = Set.of("BILLER_ACCOUNT", "BANK_ACCOUNT", "CARD_TOKEN");
  private static final Set<String> HANDLED =
      Set.of(VALIDATE, POST, "HoldFunds", "CommitHold", "ReleaseHold", "CreditWallet", "ReverseTransaction", "QueryTransaction");

  private static final Logger log = LoggerFactory.getLogger(CoreCommandService.class);

  private final CoreMockProperties props;
  private final WalletRepository wallets;
  private final LedgerRepository ledger;
  private final CommandLogRepository commandLog;
  private final CoreCommandMapper commands;
  private final CoreEventMapper events;
  private final TransferRules rules;
  private final HoldOperations holdOps;
  private final PostingOperations postingOps;
  private final StatusOperations statusOps;
  private final FaultRegistry faults;
  private final OutboxWriter outbox;
  private final JsonMapper json;
  private final Clock clock;

  @SuppressWarnings("java:S107")
  public CoreCommandService(
      CoreMockProperties props,
      WalletRepository wallets,
      LedgerRepository ledger,
      CommandLogRepository commandLog,
      CoreCommandMapper commands,
      CoreEventMapper events,
      TransferRules rules,
      HoldOperations holdOps,
      PostingOperations postingOps,
      StatusOperations statusOps,
      FaultRegistry faults,
      OutboxWriter outbox,
      JsonMapper json,
      Clock clock) {
    this.props = props;
    this.wallets = wallets;
    this.ledger = ledger;
    this.commandLog = commandLog;
    this.commands = commands;
    this.events = events;
    this.rules = rules;
    this.holdOps = holdOps;
    this.postingOps = postingOps;
    this.statusOps = statusOps;
    this.faults = faults;
    this.outbox = outbox;
    this.json = json;
    this.clock = clock;
  }

  /** Parses a Kafka record body and handles it (own transaction: no self-invocation proxying). */
  @Transactional
  public Optional<CoreEvent> handle(String body, String eventTopic) {
    return handle(commands.command(body), eventTopic);
  }

  /** As {@link #handle(CoreCommand, String)}, answering on the wallet event topic. */
  @Transactional
  public Optional<CoreEvent> handle(CoreCommand cmd) {
    return handle(cmd, EVENT_TOPIC);
  }

  /**
   * Handles one command and queues its result event on {@code eventTopic} (unless a fault drops
   * it). Returns the event the core decided on, empty (no event) for a command type the core does
   * not know and for a command that expired before it was first seen.
   */
  @Transactional
  public Optional<CoreEvent> handle(CoreCommand cmd, String eventTopic) {
    if (!HANDLED.contains(cmd.commandType())) {
      log.atWarn()
          .addKeyValue("event", "core.command.ignored")
          .addKeyValue("txnId", cmd.txnId())
          .addKeyValue("commandType", cmd.commandType())
          .log("command type not implemented by core-mock");
      return Optional.empty();
    }
    commandLog.lock(cmd.commandId());
    Optional<String> prior = commandLog.findEvent(cmd.commandId());
    if (prior.isEmpty() && cmd.expiresAt() != null && cmd.expiresAt().isBefore(clock.instant())) {
      log.atWarn()
          .addKeyValue("event", "core.command.expired")
          .addKeyValue("txnId", cmd.txnId())
          .addKeyValue("commandType", cmd.commandType())
          .log("command expired before it was handled, dropped");
      return Optional.empty();
    }
    List<Fault> hit = faults.consume(cmd.commandType(), cmd.txnId());
    CoreEvent event;
    if (prior.isPresent()) {
      event = json.readValue(prior.get(), CoreEvent.class);
    } else {
      Optional<Fault> reject =
          hit.stream().filter(f -> f.mode() == Fault.FaultMode.REJECT).findFirst();
      String failureType = CoreEventMapper.failureType(cmd.commandType());
      CoreEvent decided;
      if (reject.isPresent() && failureType != null) {
        decided = events.rejected(cmd, failureType, coreRef(), reject.get().reasonCode(), "Injected rejection");
      } else {
        decided = execute(cmd);
      }
      String stored = json.writeValueAsString(decided);
      commandLog.save(cmd.commandId(), cmd.commandType(), cmd.txnId(), stored);
      event = json.readValue(stored, CoreEvent.class);
    }
    publish(cmd, event, eventTopic, hit);
    log.atInfo()
        .addKeyValue("event", "core.command.handled")
        .addKeyValue("txnId", cmd.txnId())
        .addKeyValue("commandType", cmd.commandType())
        .addKeyValue("eventType", event.eventType())
        .addKeyValue("outcome", event.outcome().name())
        .addKeyValue("errorCode", event.reasonCode())
        .addKeyValue("replay", prior.isPresent())
        .addKeyValue("faults", hit.stream().map(f -> f.mode().name()).toList().toString())
        .log("core command handled");
    return Optional.of(event);
  }

  /** Queues the reply, shaped by the faults that hit the command. */
  private void publish(CoreCommand cmd, CoreEvent event, String eventTopic, List<Fault> hit) {
    if (hit.stream().anyMatch(f -> f.mode() == Fault.FaultMode.DROP_REPLY)) {
      return;
    }
    Instant availableAt =
        hit.stream()
            .filter(f -> f.mode() == Fault.FaultMode.DELAY)
            .map(f -> clock.instant().plusMillis(f.delayMs()))
            .max(Instant::compareTo)
            .orElse(null);
    String correlation = cmd.correlationId() == null || cmd.correlationId().isBlank() ? cmd.txnId() : cmd.correlationId();
    EventMetadata meta = new EventMetadata(correlation, cmd.txnId(), cmd.commandId(), 1);
    outbox.append(eventTopic, cmd.txnId(), meta, event, availableAt);
    if (hit.stream().anyMatch(f -> f.mode() == Fault.FaultMode.DUPLICATE_REPLY)) {
      outbox.append(eventTopic, cmd.txnId(), meta, event, availableAt);
    }
  }

  private CoreEvent execute(CoreCommand cmd) {
    String coreRef = coreRef();
    return switch (cmd.commandType()) {
      case "HoldFunds" -> holdOps.hold(cmd, coreRef);
      case "CommitHold" -> holdOps.commit(cmd, coreRef);
      case "ReleaseHold" -> holdOps.release(cmd, coreRef);
      case "CreditWallet" -> postingOps.credit(cmd, coreRef);
      case "ReverseTransaction" -> postingOps.reverse(cmd, coreRef);
      case "QueryTransaction" -> statusOps.query(cmd, coreRef);
      default -> transfer(cmd, coreRef);
    };
  }

  /** ValidateTransaction and PostTransfer. */
  private CoreEvent transfer(CoreCommand cmd, String coreRef) {
    boolean post = POST.equals(cmd.commandType());
    TransferPayload p = commands.transfer(cmd);
    String failureType = post ? "TransferRejected" : "TransactionRejected";

    if (!post && EXTERNAL_CREDIT_TYPES.contains(p.debitParty().type())) {
      // The payer is outside this core (a bank account debited by the partner, ADD_MONEY_BANK;
      // ticket 23): only the receiving wallet can be validated. The money arrives later by
      // CreditWallet; PostTransfer and HoldFunds still need a debit wallet.
      return validateCreditOnly(cmd, p, coreRef);
    }
    Optional<String> debitId = wallets.resolveId(p.debitParty().id());
    if (debitId.isEmpty()) {
      return events.rejected(cmd, failureType, coreRef, Reason.WALLET_NOT_FOUND.name(), Reason.WALLET_NOT_FOUND.message);
    }
    if (!post && EXTERNAL_CREDIT_TYPES.contains(p.creditParty().type())) {
      // The payee is outside this core (a biller or bank account, paid by a partner after a
      // hold): only the payer side can be validated. PostTransfer still needs a wallet.
      return validateDebitOnly(cmd, p, debitId.get(), coreRef);
    }
    Optional<String> creditId = wallets.resolveId(p.creditParty().id());
    if (creditId.isEmpty()) {
      return events.rejected(cmd, failureType, coreRef, Reason.CREDIT_WALLET_NOT_FOUND.name(), Reason.CREDIT_WALLET_NOT_FOUND.message);
    }
    if (debitId.get().equals(creditId.get())) {
      return reject(cmd, failureType, coreRef, Reason.SAME_WALLET);
    }
    // Lock in id order so concurrent transfers in opposite directions cannot deadlock.
    Wallet debit;
    Wallet credit;
    if (debitId.get().compareTo(creditId.get()) < 0) {
      debit = wallets.lock(debitId.get());
      credit = wallets.lock(creditId.get());
    } else {
      credit = wallets.lock(creditId.get());
      debit = wallets.lock(debitId.get());
    }

    long amount = p.amount().valueMinor();
    long fee = rules.fee(p.txnType());
    Instant dayStart = clock.instant().truncatedTo(ChronoUnit.DAYS);
    long usedToday = wallets.debitedSince(debit.id(), dayStart);

    Optional<Reason> refusal = rules.check(p, debit, credit, amount, fee, usedToday);
    if (refusal.isPresent()) {
      return reject(cmd, failureType, coreRef, refusal.get());
    }
    if (!post) {
      Map<String, Amount> left = new LinkedHashMap<>();
      left.put("PER_TXN", Amount.bdt(props.limits().perTxnMinor()));
      left.put("DAILY", Amount.bdt(props.limits().dailyMinor() - usedToday - amount));
      return events.validated(cmd, coreRef, fee, left);
    }

    Instant now = clock.instant();
    ledger.append(cmd.commandId(), cmd.txnId(), debit.id(), "DEBIT", "TRANSFER", amount, now);
    ledger.append(cmd.commandId(), cmd.txnId(), credit.id(), "CREDIT", "TRANSFER", amount, now);
    wallets.adjustBalance(debit.id(), -amount - fee);
    wallets.adjustBalance(credit.id(), amount);
    if (fee > 0) {
      ledger.append(cmd.commandId(), cmd.txnId(), debit.id(), "DEBIT", "FEE", fee, now);
      ledger.append(cmd.commandId(), cmd.txnId(), WalletSeeder.FEE_WALLET, "CREDIT", "FEE", fee, now);
      wallets.adjustBalance(WalletSeeder.FEE_WALLET, fee);
    }
    return events.posted(
        cmd,
        coreRef,
        List.of(balanceOf(debit.id()), balanceOf(credit.id())));
  }

  private CoreEvent validateDebitOnly(CoreCommand cmd, TransferPayload p, String debitId, String coreRef) {
    Wallet debit = wallets.lock(debitId);
    long amount = p.amount().valueMinor();
    long fee = rules.fee(p.txnType());
    long usedToday = wallets.debitedSince(debit.id(), clock.instant().truncatedTo(ChronoUnit.DAYS));
    Optional<Reason> refusal = rules.check(p, debit, null, amount, fee, usedToday);
    if (refusal.isPresent()) {
      return reject(cmd, "TransactionRejected", coreRef, refusal.get());
    }
    Map<String, Amount> left = new LinkedHashMap<>();
    left.put("PER_TXN", Amount.bdt(props.limits().perTxnMinor()));
    left.put("DAILY", Amount.bdt(props.limits().dailyMinor() - usedToday - amount));
    return events.validated(cmd, coreRef, fee, left);
  }

  private CoreEvent validateCreditOnly(CoreCommand cmd, TransferPayload p, String coreRef) {
    Optional<String> creditId = wallets.resolveId(p.creditParty().id());
    if (creditId.isEmpty()) {
      return reject(cmd, "TransactionRejected", coreRef, Reason.CREDIT_WALLET_NOT_FOUND);
    }
    Wallet credit = wallets.lock(creditId.get());
    Optional<Reason> refusal = rules.checkPayee(p, credit, p.amount().valueMinor());
    if (refusal.isPresent()) {
      return reject(cmd, "TransactionRejected", coreRef, refusal.get());
    }
    Map<String, Amount> left = new LinkedHashMap<>();
    left.put("PER_TXN", Amount.bdt(props.limits().perTxnMinor()));
    return events.validated(cmd, coreRef, rules.fee(p.txnType()), left);
  }

  private CoreEvent reject(CoreCommand cmd, String failureType, String coreRef, Reason reason) {
    return events.rejected(cmd, failureType, coreRef, reason.name(), reason.message);
  }

  private CoreEvent.BalanceAfter balanceOf(String walletId) {
    return new CoreEvent.BalanceAfter(walletId, Amount.bdt(wallets.get(walletId).balanceMinor()));
  }

  private String coreRef() {
    String day = clock.instant().atZone(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    return "CORE-%s-%06d".formatted(day, ledger.nextCoreSequence());
  }
}
