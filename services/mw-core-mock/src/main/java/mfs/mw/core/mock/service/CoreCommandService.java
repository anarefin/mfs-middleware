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
import mfs.mw.core.mock.dto.TransferPayload;
import mfs.mw.core.mock.entity.Wallet;
import mfs.mw.core.mock.mapper.CoreCommandMapper;
import mfs.mw.core.mock.mapper.CoreEventMapper;
import mfs.mw.core.mock.messaging.EventMetadata;
import mfs.mw.core.mock.messaging.OutboxWriter;
import mfs.mw.core.mock.repository.CommandLogRepository;
import mfs.mw.core.mock.repository.LedgerRepository;
import mfs.mw.core.mock.repository.WalletRepository;
import mfs.mw.core.mock.security.PinCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core wallet for P2P (spec 6.3): {@code ValidateTransaction} checks without moving money,
 * {@code PostTransfer} moves amount and fee in double entry. Every command is idempotent on {@code
 * commandId}: the first result event is stored verbatim in {@code command_log}, in the same
 * transaction as the ledger change and the outbox row, and a repeated command is answered with that
 * same event again. Rule order: wallets, status, PIN, amount, limits, funds.
 */
@Service
public class CoreCommandService {

  public static final String EVENT_TOPIC = "core.wallet.event.v1";
  static final String VALIDATE = "ValidateTransaction";
  static final String POST = "PostTransfer";
  private static final Set<String> HANDLED = Set.of(VALIDATE, POST);

  private static final Logger log = LoggerFactory.getLogger(CoreCommandService.class);

  /** Why the core refuses a request; the code travels as {@code reasonCode}. */
  enum Reason {
    WALLET_NOT_FOUND("Debit wallet not found"),
    CREDIT_WALLET_NOT_FOUND("Credit wallet not found"),
    ACCOUNT_BLOCKED("Debit wallet is not active"),
    CREDIT_ACCOUNT_BLOCKED("Credit wallet is not active"),
    SAME_WALLET("Debit and credit wallet are the same"),
    INVALID_PIN("Wrong PIN"),
    INVALID_PIN_BLOCK("PIN block cannot be decrypted"),
    INVALID_AMOUNT("Amount must be positive BDT"),
    LIMIT_EXCEEDED_PER_TXN("Per-transaction limit exceeded"),
    LIMIT_EXCEEDED_DAILY("Daily limit exceeded"),
    INSUFFICIENT_BALANCE("Balance does not cover amount and fee");

    final String message;

    Reason(String message) {
      this.message = message;
    }
  }

  private final CoreMockProperties props;
  private final WalletRepository wallets;
  private final LedgerRepository ledger;
  private final CommandLogRepository commandLog;
  private final CoreCommandMapper commands;
  private final CoreEventMapper events;
  private final PinCipher pins;
  private final PasswordEncoder encoder;
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
      PinCipher pins,
      PasswordEncoder encoder,
      OutboxWriter outbox,
      JsonMapper json,
      Clock clock) {
    this.props = props;
    this.wallets = wallets;
    this.ledger = ledger;
    this.commandLog = commandLog;
    this.commands = commands;
    this.events = events;
    this.pins = pins;
    this.encoder = encoder;
    this.outbox = outbox;
    this.json = json;
    this.clock = clock;
  }

  /** Parses a Kafka record body and handles it (own transaction: no self-invocation proxying). */
  @Transactional
  public Optional<CoreEvent> handle(String body) {
    return handle(commands.command(body));
  }

  /**
   * Handles one command and queues its result event on {@link #EVENT_TOPIC}. Empty (no event) for
   * a command type outside this slice and for a command that expired before it was first seen.
   */
  @Transactional
  public Optional<CoreEvent> handle(CoreCommand cmd) {
    if (!HANDLED.contains(cmd.commandType())) {
      log.atWarn()
          .addKeyValue("event", "core.command.ignored")
          .addKeyValue("txnId", cmd.txnId())
          .addKeyValue("commandType", cmd.commandType())
          .log("command type not implemented by core-mock yet");
      return Optional.empty();
    }
    commandLog.lock(cmd.commandId());
    Optional<String> prior = commandLog.findEvent(cmd.commandId());
    CoreEvent event;
    if (prior.isPresent()) {
      event = json.readValue(prior.get(), CoreEvent.class);
    } else {
      if (cmd.expiresAt() != null && cmd.expiresAt().isBefore(clock.instant())) {
        log.atWarn()
            .addKeyValue("event", "core.command.expired")
            .addKeyValue("txnId", cmd.txnId())
            .addKeyValue("commandType", cmd.commandType())
            .log("command expired before it was handled, dropped");
        return Optional.empty();
      }
      String stored = json.writeValueAsString(execute(cmd));
      commandLog.save(cmd.commandId(), cmd.commandType(), cmd.txnId(), stored);
      event = json.readValue(stored, CoreEvent.class);
    }
    String correlation = cmd.correlationId() == null || cmd.correlationId().isBlank() ? cmd.txnId() : cmd.correlationId();
    outbox.append(EVENT_TOPIC, cmd.txnId(), new EventMetadata(correlation, cmd.txnId(), cmd.commandId(), 1), event);
    log.atInfo()
        .addKeyValue("event", "core.command.handled")
        .addKeyValue("txnId", cmd.txnId())
        .addKeyValue("commandType", cmd.commandType())
        .addKeyValue("eventType", event.eventType())
        .addKeyValue("outcome", event.outcome().name())
        .addKeyValue("errorCode", event.reasonCode())
        .addKeyValue("replay", prior.isPresent())
        .log("core command handled");
    return Optional.of(event);
  }

  private CoreEvent execute(CoreCommand cmd) {
    boolean post = POST.equals(cmd.commandType());
    TransferPayload p = commands.transfer(cmd);
    String coreRef = coreRef();
    String failureType = post ? "TransferRejected" : "TransactionRejected";

    Optional<String> debitId = wallets.resolveId(p.debitParty().id());
    if (debitId.isEmpty()) {
      return events.rejected(cmd, failureType, coreRef, Reason.WALLET_NOT_FOUND.name(), Reason.WALLET_NOT_FOUND.message);
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
    long fee = props.fees().getOrDefault(p.txnType(), 0L);
    Instant dayStart = clock.instant().truncatedTo(ChronoUnit.DAYS);
    long usedToday = wallets.debitedSince(debit.id(), dayStart);

    Optional<Reason> refusal = check(p, debit, credit, amount, fee, usedToday);
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

  private Optional<Reason> check(
      TransferPayload p, Wallet debit, Wallet credit, long amount, long fee, long usedToday) {
    if (!debit.active()) {
      return Optional.of(Reason.ACCOUNT_BLOCKED);
    }
    if (!credit.active()) {
      return Optional.of(Reason.CREDIT_ACCOUNT_BLOCKED);
    }
    Optional<String> pin = pins.decrypt(p.encryptedPin());
    if (pin.isEmpty()) {
      return Optional.of(Reason.INVALID_PIN_BLOCK);
    }
    if (!encoder.matches(pin.get(), debit.pinHash())) {
      return Optional.of(Reason.INVALID_PIN);
    }
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
