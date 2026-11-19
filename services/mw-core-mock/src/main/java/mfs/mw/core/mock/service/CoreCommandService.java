package mfs.mw.core.mock.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.dto.FactorsPayload;
import mfs.mw.core.mock.dto.Fault;
import mfs.mw.core.mock.dto.TransferPayload;
import mfs.mw.core.mock.dto.UnlockPayload;
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
  static final String VERIFY_FACTORS = "VerifyCustomerFactors";
  static final String BALANCE = "BalanceInquiry";
  /** Party types that are never a wallet of this core: paid outside it by a partner. */
  static final Set<String> EXTERNAL_CREDIT_TYPES = Set.of("BILLER_ACCOUNT", "BANK_ACCOUNT", "CARD_TOKEN");
  /**
   * Payees validated on the payer side only: the external parties plus {@code MSISDN}, the mobile
   * number a TOPUP recharges (airtime from the operator, the money goes to SETTLEMENT): it need not
   * have a wallet, and may be the payer's own number.
   */
  static final Set<String> PAYER_SIDE_ONLY_PAYEES = Set.of("BILLER_ACCOUNT", "BANK_ACCOUNT", "CARD_TOKEN", "MSISDN");
  private static final Set<String> HANDLED =
      Set.of(VALIDATE, POST, "HoldFunds", "CommitHold", "ReleaseHold", "CreditWallet", "ReverseTransaction", "QueryTransaction", "UnlockWallet", VERIFY_FACTORS,
          BALANCE);

  private static final Logger log = LoggerFactory.getLogger(CoreCommandService.class);

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
  private final WalletStateEvents stateEvents;
  private final AssertionCheck assertions;
  private final JsonMapper json;
  private final Clock clock;

  @SuppressWarnings("java:S107")
  public CoreCommandService(
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
      WalletStateEvents stateEvents,
      AssertionCheck assertions,
      JsonMapper json,
      Clock clock) {
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
    this.stateEvents = stateEvents;
    this.assertions = assertions;
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
      if (reject.isPresent() && VERIFY_FACTORS.equals(cmd.commandType())) {
        decided = events.factorsRejected(cmd, coreRef(), reject.get().reasonCode(), "Injected rejection",
            commands.factors(cmd).resetId());
      } else if (reject.isPresent() && failureType != null) {
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
      case "UnlockWallet" -> unlock(cmd, coreRef);
      case VERIFY_FACTORS -> verifyFactors(cmd, coreRef);
      case BALANCE -> balance(cmd, coreRef);
      default -> transfer(cmd, coreRef);
    };
  }

  /**
   * UnlockWallet (ticket 52): lifts a core-side lock (status LOCKED -> ACTIVE; since ticket 72 the core
   * has no PIN lockout). A wallet that is not LOCKED (active, or blocked for another reason) is refused
   * {@code WALLET_NOT_LOCKED} and left as it is; an unknown one {@code WALLET_NOT_FOUND}.
   */
  private CoreEvent unlock(CoreCommand cmd, String coreRef) {
    UnlockPayload p = commands.unlock(cmd);
    Optional<String> id = wallets.resolveId(p.subject().id());
    if (id.isEmpty()) {
      return reject(cmd, "UnlockRejected", coreRef, Reason.WALLET_NOT_FOUND);
    }
    if (!wallets.unlock(id.get())) {
      return reject(cmd, "UnlockRejected", coreRef, Reason.WALLET_NOT_LOCKED);
    }
    stateEvents.statusChanged(id.get());
    log.atInfo()
        .addKeyValue("event", "wallet.unlocked")
        .addKeyValue("txnId", cmd.txnId())
        .addKeyValue("walletType", wallets.get(id.get()).type())
        .log("wallet lock lifted by ops");
    return events.succeeded(cmd, "WalletUnlocked", coreRef, null, Map.of("walletId", id.get()));
  }

  /**
   * VerifyCustomerFactors (ticket 70): mw-auth's self-service PIN reset asks whether the KYC factors
   * match. The factors come as TOKENISE_NID hashes the mock cannot recompute (it holds no KYC data and
   * no mw-crypto key), so it checks only the wallet: unknown {@code WALLET_NOT_FOUND}, BLOCKED or HOLD
   * {@code WALLET_NOT_ACTIVE} (a core-side lock, LOCKED, does not stop a reset), else verified. A
   * mismatch is played with a REJECT fault ({@code reasonCode FACTORS_MISMATCH}). A real core compares
   * the hashes with its own KYC record under the shared tokenisation (open point for the core team).
   */
  private CoreEvent verifyFactors(CoreCommand cmd, String coreRef) {
    FactorsPayload p = commands.factors(cmd);
    Optional<String> id = wallets.resolveId(p.subject().id());
    if (id.isEmpty()) {
      return events.factorsRejected(cmd, coreRef, Reason.WALLET_NOT_FOUND.name(), Reason.WALLET_NOT_FOUND.message,
          p.resetId());
    }
    Wallet w = wallets.get(id.get());
    if (!w.active() && !w.locked()) {
      return events.factorsRejected(cmd, coreRef, Reason.WALLET_NOT_ACTIVE.name(), Reason.WALLET_NOT_ACTIVE.message,
          p.resetId());
    }
    log.atInfo().addKeyValue("event", "core.kyc.factors_verified").addKeyValue("txnId", cmd.txnId())
        .log("KYC factors of a PIN reset accepted");
    return events.succeeded(cmd, "CustomerFactorsVerified", coreRef, null,
        Map.of("walletId", id.get(), "resetId", p.resetId()));
  }

  /**
   * BalanceInquiry (ADR D26): the available balance (balance minus holds) of an ACTIVE wallet,
   * authorised by mw-auth's assertion (subject the wallet, as for a transfer). Unknown {@code
   * WALLET_NOT_FOUND}, not ACTIVE (LOCKED, BLOCKED, HOLD) {@code WALLET_NOT_ACTIVE}, a refused
   * assertion {@code MW-AUTH-046}. No ledger entry, no daily-limit use; the amount is never logged.
   */
  private CoreEvent balance(CoreCommand cmd, String coreRef) {
    TransferPayload p = commands.balance(cmd);
    Optional<String> id = wallets.resolveId(p.debitParty().id());
    if (id.isEmpty()) {
      return reject(cmd, "BalanceInquiryRejected", coreRef, Reason.WALLET_NOT_FOUND);
    }
    Wallet w = wallets.get(id.get());
    if (!w.active()) {
      return events.rejected(cmd, "BalanceInquiryRejected", coreRef, Reason.WALLET_NOT_ACTIVE.code(),
          Reason.WALLET_NOT_ACTIVE.message, Map.of("walletId", w.id()));
    }
    Optional<Reason> refused = assertions.check(p, w, cmd.txnId());
    if (refused.isPresent()) {
      return events.rejected(cmd, "BalanceInquiryRejected", coreRef, refused.get().code(), refused.get().message,
          Map.of("walletId", w.id()));
    }
    log.atInfo().addKeyValue("event", "core.balance.reported").addKeyValue("txnId", cmd.txnId())
        .addKeyValue("walletType", w.type()).log("wallet balance reported");
    return events.balanceReported(cmd, coreRef, w.id(), Amount.bdt(w.availableMinor()));
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
    if (!post && PAYER_SIDE_ONLY_PAYEES.contains(p.creditParty().type())) {
      // The payee is outside this core (a biller or bank account, or the number a top-up
      // recharges; paid by a partner after a hold): only the payer side can be validated.
      // PostTransfer still needs a wallet.
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
    Charges charges = rules.charges(p.txnType(), null, debit.type(), amount);

    Optional<Reason> refusal = rules.check(cmd.txnId(), p, debit, credit, amount, charges.fee());
    if (refusal.isPresent()) {
      return reject(cmd, failureType, coreRef, refusal.get());
    }
    if (!post) {
      return events.validated(cmd, coreRef, charges.fee(), charges.vat(), TransferRules.left(rules.limits(p.txnType(), debit, amount), amount));
    }

    ledger.post(cmd.commandId(), cmd.txnId(), p.txnType(), Legs.payment(debit.id(), credit.id(), amount, charges),
        clock.instant());
    return events.posted(
        cmd,
        coreRef,
        List.of(balanceOf(debit.id()), balanceOf(credit.id())));
  }

  private CoreEvent validateDebitOnly(CoreCommand cmd, TransferPayload p, String debitId, String coreRef) {
    Wallet debit = wallets.lock(debitId);
    long amount = p.amount().valueMinor();
    Charges charges = rules.charges(p.txnType(), null, debit.type(), amount);
    Optional<Reason> refusal = rules.check(cmd.txnId(), p, debit, null, amount, charges.fee());
    if (refusal.isPresent()) {
      return reject(cmd, "TransactionRejected", coreRef, refusal.get());
    }
    return events.validated(cmd, coreRef, charges.fee(), charges.vat(), TransferRules.left(rules.limits(p.txnType(), debit, amount), amount));
  }

  private CoreEvent validateCreditOnly(CoreCommand cmd, TransferPayload p, String coreRef) {
    Optional<String> creditId = wallets.resolveId(p.creditParty().id());
    if (creditId.isEmpty()) {
      return reject(cmd, "TransactionRejected", coreRef, Reason.CREDIT_WALLET_NOT_FOUND);
    }
    Wallet credit = wallets.lock(creditId.get());
    long amount = p.amount().valueMinor();
    Optional<Reason> refusal = rules.checkPayee(cmd.txnId(), p, credit, amount);
    if (refusal.isPresent()) {
      return reject(cmd, "TransactionRejected", coreRef, refusal.get());
    }
    Map<String, Amount> left = new LinkedHashMap<>();
    Long perTxn = rules.rule(p.txnType(), credit).perTxnMax();
    if (perTxn != null) {
      left.put("PER_TXN", Amount.bdt(perTxn));
    }
    Charges charges = rules.charges(p.txnType(), null, credit.type(), amount);
    return events.validated(cmd, coreRef, charges.fee(), charges.vat(), left);
  }

  private CoreEvent reject(CoreCommand cmd, String failureType, String coreRef, Reason reason) {
    return events.rejected(cmd, failureType, coreRef, reason.code(), reason.message);
  }

  private CoreEvent.BalanceAfter balanceOf(String walletId) {
    return new CoreEvent.BalanceAfter(walletId, Amount.bdt(wallets.get(walletId).balanceMinor()));
  }

  private String coreRef() {
    String day = clock.instant().atZone(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    return "CORE-%s-%06d".formatted(day, ledger.nextCoreSequence());
  }
}
