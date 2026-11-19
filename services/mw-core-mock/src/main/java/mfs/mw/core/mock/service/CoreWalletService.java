package mfs.mw.core.mock.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import mfs.mw.core.mock.config.CoreMockProperties;
import mfs.mw.core.mock.dto.AccountView;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.BalanceView;
import mfs.mw.core.mock.dto.ChargesRequest;
import mfs.mw.core.mock.dto.ChargesView;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.dto.EntryView;
import mfs.mw.core.mock.dto.HoldActionRequest;
import mfs.mw.core.mock.dto.LimitsRequest;
import mfs.mw.core.mock.dto.LimitsView;
import mfs.mw.core.mock.dto.OperationResult;
import mfs.mw.core.mock.dto.ReversalRequest;
import mfs.mw.core.mock.dto.StatementView;
import mfs.mw.core.mock.dto.TransactionStatusView;
import mfs.mw.core.mock.dto.WalletCreditRequest;
import mfs.mw.core.mock.dto.WalletTransferRequest;
import mfs.mw.core.mock.entity.Hold;
import mfs.mw.core.mock.entity.LedgerEntry;
import mfs.mw.core.mock.entity.PostedEntry;
import mfs.mw.core.mock.entity.Wallet;
import mfs.mw.core.mock.exception.ErrorCodes;
import mfs.mw.core.mock.exception.MwException;
import mfs.mw.core.mock.repository.CommandLogRepository;
import mfs.mw.core.mock.repository.HoldRepository;
import mfs.mw.core.mock.repository.LedgerRepository;
import mfs.mw.core.mock.repository.RestRequestRepository;
import mfs.mw.core.mock.repository.WalletRepository;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core wallet over REST (FRS 7.2, ADR D31, ticket 93). A money operation is the Kafka command
 * of the same name, built here and handed to {@link CoreCommandService}: the same rules ({@link
 * HoldOperations}, {@link PostingOperations}, {@link TransferRules}, {@link AssertionCheck}), the same
 * {@code command_log} idempotency and fault injection, and the same {@code core.event.v1} result
 * through the outbox in the transaction of the ledger change (holds on the payment event topic, the
 * rest on the wallet one), so mw-auth's projection and the ledger views keep working.
 *
 * <p>Idempotent by txnId and operation (FRS 7.3): the commandId is {@code <txnId>:rest:<op>}, and
 * {@code rest_request} keeps the hash of the first body. A repeat with that body is answered from the
 * stored result event (no second posting), another body is {@code 409 MW-IDEM-001}. A refusal is a
 * stored result too ({@code 422 MW-CORE-<reason>}, the assertion's {@code 401 MW-AUTH-046}): its
 * transaction commits, so a repeat gets the same refusal. Reads change nothing and write no event.
 */
@Service
public class CoreWalletService {

  static final String REST_OP = ":rest:";

  private final CoreCommandService core;
  private final StatusOperations status;
  private final TransferRules rules;
  private final CoreMockProperties props;
  private final WalletRepository wallets;
  private final HoldRepository holds;
  private final LedgerRepository ledger;
  private final CommandLogRepository commandLog;
  private final RestRequestRepository requests;
  private final JsonMapper json;
  private final Clock clock;

  @SuppressWarnings("java:S107")
  public CoreWalletService(
      CoreCommandService core,
      StatusOperations status,
      TransferRules rules,
      CoreMockProperties props,
      WalletRepository wallets,
      HoldRepository holds,
      LedgerRepository ledger,
      CommandLogRepository commandLog,
      RestRequestRepository requests,
      JsonMapper json,
      Clock clock) {
    this.core = core;
    this.status = status;
    this.rules = rules;
    this.props = props;
    this.wallets = wallets;
    this.holds = holds;
    this.ledger = ledger;
    this.commandLog = commandLog;
    this.requests = requests;
    this.json = json;
    this.clock = clock;
  }

  // --- reads ---

  /** WAL-01: the account by wallet id or MSISDN; unknown 404 {@code MW-VAL-003}. */
  @Transactional(readOnly = true)
  public AccountView account(String msisdn) {
    Wallet w = wallet(msisdn);
    return new AccountView(w.id(), w.msisdn(), w.type(), w.status(), w.holderName());
  }

  /** WAL-03: available (total minus held), held and total. */
  @Transactional(readOnly = true)
  public BalanceView balance(String walletId) {
    Wallet w = wallet(walletId);
    return new BalanceView(w.id(), Amount.bdt(w.availableMinor()), Amount.bdt(w.heldMinor()), Amount.bdt(w.balanceMinor()));
  }

  /** WAL-04: the flat fee of the txnType; VAT and commission 0 until the tariff sheet (ticket 94). */
  public ChargesView charges(ChargesRequest req) {
    long fee = rules.fee(req.txnType());
    return new ChargesView(req.txnType(), req.amount(), Amount.bdt(fee), Amount.bdt(0), Amount.bdt(0),
        Amount.bdt(req.amount().valueMinor() + fee));
  }

  /**
   * WAL-05: the per-transaction and the daily (UTC, the wallet type's) debit limit, as {@link
   * TransferRules} applies them; {@code remaining.daily} is what is left before this amount. An
   * unknown wallet is 422 {@code MW-CORE-WALLET_NOT_FOUND}.
   */
  @Transactional(readOnly = true)
  public LimitsView limits(LimitsRequest req) {
    Optional<String> id = wallets.resolveId(req.walletId());
    if (id.isEmpty()) {
      throw refusal(Reason.WALLET_NOT_FOUND.code(), Reason.WALLET_NOT_FOUND.message, null);
    }
    Wallet w = wallets.get(id.get());
    long amount = req.amount().valueMinor();
    long perTxn = props.limits().perTxnMinor();
    long used = wallets.debitedSince(w.id(), clock.instant().truncatedTo(ChronoUnit.DAYS));
    long daily = props.limits().dailyMinor(w.type());
    String reason = amount > perTxn ? Reason.LIMIT_EXCEEDED_PER_TXN.code()
        : used + amount > daily ? Reason.LIMIT_EXCEEDED_DAILY.code() : null;
    return new LimitsView(w.id(), reason == null, reason,
        new LimitsView.Remaining(Amount.bdt(perTxn), Amount.bdt(Math.max(0, daily - used))));
  }

  /** WAL-12: the wallet's ledger entries in [from, to), oldest first, at most {@code limit}. */
  @Transactional(readOnly = true)
  public StatementView statement(String walletId, Instant from, Instant to, int limit) {
    Wallet w = wallet(walletId);
    return new StatementView(w.id(), from, to, ledger.postedOfWallet(w.id(), from, to, limit).stream().map(CoreWalletService::view).toList());
  }

  /** WAL-13: the core's true status (as QueryTransaction tells it) and every entry of the txn. */
  @Transactional(readOnly = true)
  public TransactionStatusView transaction(String txnId) {
    StatusOperations.TxnStatus s = status.current(txnId);
    return new TransactionStatusView(txnId, s.status(), s.holdId(), ledger.postedOf(txnId).stream().map(CoreWalletService::view).toList());
  }

  // --- money ---

  /** WAL-06: HoldFunds. */
  @Transactional(noRollbackFor = MwException.class)
  public OperationResult hold(WalletTransferRequest req, String assertion) {
    return run(req.txnId(), "hold", "HoldFunds", CoreCommandService.PAYMENT_EVENT_TOPIC, req, transferPayload(req, assertion),
        e -> result(e, "HELD", null));
  }

  /** WAL-07: CommitHold, under the hold's txnId; an unknown hold is 422 {@code MW-CORE-HOLD_NOT_FOUND}. */
  @Transactional(noRollbackFor = MwException.class)
  public OperationResult commit(String holdId, HoldActionRequest req) {
    Hold hold = holdOf(holdId);
    return run(hold.txnId(), "commit", "CommitHold", CoreCommandService.PAYMENT_EVENT_TOPIC, req, holdPayload(holdId, req),
        e -> result(e, "COMMITTED", null));
  }

  /** WAL-08: ReleaseHold, under the hold's txnId. */
  @Transactional(noRollbackFor = MwException.class)
  public OperationResult release(String holdId, HoldActionRequest req) {
    Hold hold = holdOf(holdId);
    return run(hold.txnId(), "release", "ReleaseHold", CoreCommandService.PAYMENT_EVENT_TOPIC, req, holdPayload(holdId, req),
        e -> result(e, "RELEASED", null));
  }

  /** WAL-09: PostTransfer. */
  @Transactional(noRollbackFor = MwException.class)
  public OperationResult transfer(WalletTransferRequest req, String assertion) {
    return run(req.txnId(), "transfer", "PostTransfer", CoreCommandService.EVENT_TOPIC, req, transferPayload(req, assertion),
        e -> result(e, "POSTED", null));
  }

  /** WAL-10: CreditWallet from the settlement wallet (no assertion, no fee). */
  @Transactional(noRollbackFor = MwException.class)
  public OperationResult credit(WalletCreditRequest req) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("txnType", req.txnType());
    p.put("creditParty", req.creditParty());
    p.put("amount", req.amount());
    return run(req.txnId(), "credit", "CreditWallet", CoreCommandService.EVENT_TOPIC, req, p, e -> result(e, "CREDITED", null));
  }

  /**
   * WAL-11: ReverseTransaction. Without {@code amount} the whole original (its transfer debits); the
   * command's rules then hold as on Kafka (a partial amount is AMOUNT_MISMATCH until ticket 116).
   */
  @Transactional(noRollbackFor = MwException.class)
  public OperationResult reverse(ReversalRequest req) {
    Amount amount = req.amount() != null ? req.amount() : Amount.bdt(ledger.entriesOf(req.originalTxnId()).stream()
        .filter(e -> e.debit() && "TRANSFER".equals(e.kind())).mapToLong(LedgerEntry::amountMinor).sum());
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("originalTxnId", req.originalTxnId());
    p.put("amount", amount);
    p.put("reason", req.reason());
    return run(req.txnId(), "reversal", "ReverseTransaction", CoreCommandService.EVENT_TOPIC, req, p,
        e -> result(e, "REVERSED", req.originalTxnId()));
  }

  /**
   * One money operation: under the operation's lock, the body hash checked (409 on another body) or
   * recorded, then the command through {@link CoreCommandService#handle(CoreCommand, String)}
   * (answered from {@code command_log} on a repeat), its result event turned into the answer or the
   * refusal.
   */
  private OperationResult run(String txnId, String op, String commandType, String eventTopic, Object request,
      Map<String, Object> payload, Function<CoreEvent, OperationResult> answer) {
    String commandId = txnId + REST_OP + op;
    commandLog.lock(commandId);
    String hash = hash(request);
    Optional<String> first = requests.hashOf(commandId);
    if (first.isPresent() && !first.get().equals(hash)) {
      throw new MwException(ErrorCodes.IDEM_CONFLICT, "txnId already used for this operation with another body").withTxnId(txnId);
    }
    if (first.isEmpty()) {
      requests.save(commandId, hash);
    }
    String correlation = MDC.get("correlationId");
    CoreCommand cmd = new CoreCommand(1, commandType, commandId, txnId, correlation == null ? txnId : correlation,
        clock.instant(), null, json.valueToTree(payload));
    CoreEvent event = core.handle(cmd, eventTopic)
        .orElseThrow(() -> new IllegalStateException("core gave no result for " + commandType));
    if (event.outcome() == CoreEvent.Outcome.FAILURE) {
      throw refusal(event.reasonCode(), event.reasonMessage(), txnId);
    }
    return answer.apply(event);
  }

  private static Map<String, Object> transferPayload(WalletTransferRequest req, String assertion) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("txnType", req.txnType());
    p.put("debitParty", req.debitParty());
    p.put("creditParty", req.creditParty());
    p.put("amount", req.amount());
    p.put("assertion", assertion);
    p.put("idempotencyKey", req.idempotencyKey());
    return p;
  }

  private static Map<String, Object> holdPayload(String holdId, HoldActionRequest req) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("holdId", holdId);
    p.put("reason", req.reason());
    return p;
  }

  private Hold holdOf(String holdId) {
    return holds.find(holdId)
        .orElseThrow(() -> refusal(Reason.HOLD_NOT_FOUND.code(), Reason.HOLD_NOT_FOUND.message, null));
  }

  private static OperationResult result(CoreEvent e, String state, String originalTxnId) {
    Object holdId = e.payload() == null ? null : e.payload().get("holdId");
    return new OperationResult(e.txnId(), state, e.coreRef(), holdId == null ? null : holdId.toString(), originalTxnId,
        e.balancesAfter());
  }

  /**
   * A core refusal as the API answers it: the assertion's {@code MW-AUTH-046} as it is (401), any
   * other reason {@code MW-CORE-<reason>} (422), the reason reduced to the code alphabet (an injected
   * fault may name anything).
   */
  private static MwException refusal(String reasonCode, String message, String txnId) {
    String code = ErrorCodes.AUTH_ASSERTION.equals(reasonCode) ? reasonCode
        : ErrorCodes.CORE_PREFIX + (reasonCode == null || reasonCode.isBlank() ? "REJECTED"
            : reasonCode.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]", "_"));
    MwException e = new MwException(code, message == null ? "Refused by the core" : message);
    return txnId == null ? e : e.withTxnId(txnId);
  }

  private Wallet wallet(String idOrMsisdn) {
    return wallets.resolveId(idOrMsisdn).map(wallets::get)
        .orElseThrow(() -> new MwException(ErrorCodes.VAL_NOT_FOUND, "Account not found").withStatus(HttpStatus.NOT_FOUND));
  }

  private String hash(Object request) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static EntryView view(PostedEntry e) {
    return new EntryView(e.txnId(), e.walletId(), e.direction(), e.kind(), Amount.bdt(e.amountMinor()), e.at());
  }
}
