package mfs.mw.core.mock.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import mfs.mw.core.mock.dto.AccountActionResult;
import mfs.mw.core.mock.dto.AccountUnlockRequest;
import mfs.mw.core.mock.dto.AccountView;
import mfs.mw.core.mock.dto.FactorsRequest;
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
import mfs.mw.core.mock.dto.TransferPayload;
import mfs.mw.core.mock.dto.ValidationResult;
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
import tools.jackson.databind.JsonNode;
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
  /** Callers that read any wallet's balance without the customer's assertion (ops, ticket 95a). */
  static final java.util.Set<String> OPS_READERS = java.util.Set.of("mw-admin-api", "mw-auth");

  private final CoreCommandService core;
  private final StatusOperations status;
  private final TransferRules rules;
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
    return new AccountView(w.id(), w.msisdn(), w.type(), w.status(), w.kycLevel(), w.holderName());
  }

  /** WAL-03: available (total minus held), held and total, as an ops or system read. */
  @Transactional(readOnly = true)
  public BalanceView balance(String walletId) {
    Wallet w = wallet(walletId);
    return new BalanceView(w.id(), Amount.bdt(w.availableMinor()), Amount.bdt(w.heldMinor()), Amount.bdt(w.balanceMinor()),
        clock.instant());
  }

  /**
   * How a balance is asked for (ticket 95a): the caller ({@code azp}) and, for a customer's own read,
   * the transaction it belongs to with mw-auth's assertion and the idempotency key it was signed over.
   */
  public record BalanceRead(String caller, String txnId, String assertion, String idempotencyKey) {}

  /**
   * WAL-03 with the customer's authorisation (ticket 95a, ADR D26): with an assertion, the BALANCE
   * check's BalanceInquiry under {@code <txnId>:rest:balance} (assertion and jti checked, wallet ACTIVE,
   * idempotent, faults applied), {@code available} as that inquiry reported it. Without one, a plain
   * read, allowed for ops callers ({@link #OPS_READERS}) and system wallets only: the orchestrator reads a
   * customer's balance for the customer, so it is 401 {@code MW-AUTH-046} there.
   */
  @Transactional(noRollbackFor = MwException.class)
  public BalanceView balance(String walletId, BalanceRead read) {
    if (read.assertion() == null || read.assertion().isBlank()) {
      Wallet w = wallet(walletId);
      if (!OPS_READERS.contains(read.caller()) && !"SYSTEM".equals(w.type())) {
        throw new MwException(ErrorCodes.AUTH_ASSERTION, "Transaction authorisation missing");
      }
      return balance(walletId);
    }
    if (read.txnId() == null || read.idempotencyKey() == null || read.idempotencyKey().isBlank()) {
      throw new MwException(ErrorCodes.AUTH_ASSERTION, "Transaction authorisation needs its txnId and idempotency key");
    }
    Map<String, Object> wallet = Map.of("type", "WALLET", "id", walletId);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("txnType", "BALANCE");
    p.put("debitParty", wallet);
    p.put("creditParty", wallet);
    p.put("amount", Amount.bdt(0));
    p.put("assertion", read.assertion());
    p.put("idempotencyKey", read.idempotencyKey());
    return run(read.txnId(), "balance", "BalanceInquiry", CoreCommandService.EVENT_TOPIC,
        // the assertion's hash is part of the request: another assertion under the same txnId and key is a
        // 409 conflict, never the stored answer (security review of ticket 95a)
        new java.util.TreeMap<>(Map.of("walletId", walletId, "idempotencyKey", read.idempotencyKey(),
            "assertion", sha256(read.assertion()))), p,
        e -> {
          // a repeat is answered from the stored BalanceInquiry event alone, as at its first answer
          JsonNode body = payload(e);
          Amount available = json.treeToValue(body.path("available"), Amount.class);
          Amount held = body.has("held") ? json.treeToValue(body.path("held"), Amount.class) : Amount.bdt(0);
          Amount total = body.has("total") ? json.treeToValue(body.path("total"), Amount.class)
              : Amount.bdt(available.valueMinor() + held.valueMinor());
          return new BalanceView(body.path("walletId").asString(), available, held, total,
              Instant.parse(body.path("asOf").asString()));
        });
  }

  /**
   * WAL-04: the tariff sheet's charges of the txnType, amount, channel and payer type (default
   * CUSTOMER; ticket 94): fee (VAT included), the VAT in it, the commission shares, total = amount + fee.
   */
  @Transactional(readOnly = true)
  public ChargesView charges(ChargesRequest req) {
    long amount = req.amount().valueMinor();
    Charges c = rules.charges(req.txnType(), req.channel(), req.customerType() == null ? "CUSTOMER" : req.customerType(),
        amount);
    Map<String, Amount> split = new LinkedHashMap<>();
    c.commissionSplit().forEach((party, share) -> split.put(party, Amount.bdt(share)));
    return new ChargesView(req.txnType(), req.amount(), Amount.bdt(c.fee()), Amount.bdt(c.vat()), Amount.bdt(c.commission()),
        split, Amount.bdt(amount + c.fee()));
  }

  /**
   * WAL-05: the payer's limit rule (wallet type, KYC level, txnType; ticket 94) as {@link TransferRules}
   * applies it: per transaction min/max, daily and monthly amount and count (UTC); {@code remaining} is
   * what is left before this amount. An unknown wallet is 422 {@code MW-CORE-WALLET_NOT_FOUND}.
   */
  @Transactional(readOnly = true)
  public LimitsView limits(LimitsRequest req) {
    Optional<String> id = wallets.resolveId(req.walletId());
    if (id.isEmpty()) {
      throw refusal(Reason.WALLET_NOT_FOUND.code(), Reason.WALLET_NOT_FOUND.message, null);
    }
    Wallet w = wallets.get(id.get());
    Limits.Result r = rules.limits(req.txnType(), w, req.amount().valueMinor());
    return new LimitsView(w.id(), r.reason().isEmpty(), r.reason().map(Reason::code).orElse(null),
        new LimitsView.Remaining(bdt(r.perTxnMax()), bdt(r.dailyLeft()), bdt(r.monthlyLeft()), r.dailyCountLeft(),
            r.monthlyCountLeft()));
  }

  private static Amount bdt(Long minor) {
    return minor == null ? null : Amount.bdt(minor);
  }

  /**
   * WAL-12: the wallet's ledger entries in [from, to), oldest first, page {@code page} (from 0) of
   * {@code limit}, each with the wallet's balance after it (ticket 94).
   */
  @Transactional(readOnly = true)
  public StatementView statement(String walletId, Instant from, Instant to, int limit, int page) {
    Wallet w = wallet(walletId);
    List<PostedEntry> rows = ledger.postedOfWallet(w.id(), from, to, limit + 1, (long) page * limit);
    boolean more = rows.size() > limit;
    return new StatementView(w.id(), from, to, page, more,
        rows.stream().limit(limit).map(CoreWalletService::view).toList());
  }

  /** WAL-13: the core's true status (as QueryTransaction tells it) and every entry of the txn. */
  @Transactional(readOnly = true)
  public TransactionStatusView transaction(String txnId) {
    StatusOperations.TxnStatus s = status.current(txnId);
    return new TransactionStatusView(txnId, s.status(), s.holdId(), ledger.postedOf(txnId).stream().map(CoreWalletService::view).toList());
  }

  // --- money ---

  /**
   * ValidateTransaction over REST (ticket 95a): the same rules as the Kafka command (payer and payee,
   * limits, fee, the assertion with its jti claimed for this txn), nothing moved, idempotent under
   * {@code <txnId>:rest:validate}.
   */
  @Transactional(noRollbackFor = MwException.class)
  public ValidationResult validate(WalletTransferRequest req, String assertion) {
    return run(req.txnId(), "validate", "ValidateTransaction", CoreCommandService.EVENT_TOPIC, req,
        transferPayload(req, assertion), e -> new ValidationResult(e.txnId(), "VALIDATED", e.coreRef(),
            amount(e, "fee"), vat(e), limitsLeft(e)));
  }

  private JsonNode payload(CoreEvent e) {
    return e.payload() == null ? json.createObjectNode() : json.valueToTree(e.payload());
  }

  private Amount amount(CoreEvent e, String field) {
    JsonNode body = payload(e);
    return body.has(field) ? json.treeToValue(body.path(field), Amount.class) : Amount.bdt(0);
  }

  /** The VAT line of the event's charges (absent: 0). */
  private Amount vat(CoreEvent e) {
    for (JsonNode line : payload(e).path("charges")) {
      if ("VAT".equals(line.path("code").asString())) {
        return json.treeToValue(line.path("amount"), Amount.class);
      }
    }
    return Amount.bdt(0);
  }

  private Map<String, Amount> limitsLeft(CoreEvent e) {
    Map<String, Amount> left = new LinkedHashMap<>();
    payload(e).path("limitsLeft").properties().forEach(f -> left.put(f.getKey(), json.treeToValue(f.getValue(), Amount.class)));
    return left.isEmpty() ? null : left;
  }

  /** WAL-06: HoldFunds. */
  @Transactional(noRollbackFor = MwException.class)
  public OperationResult hold(WalletTransferRequest req, String assertion) {
    Map<String, Object> payload = transferPayload(req, assertion);
    if (req.settlementWallet() != null) {
      payload.put("attributes", Map.of(TransferPayload.SETTLEMENT_WALLET, req.settlementWallet()));
    }
    return run(req.txnId(), "hold", "HoldFunds", CoreCommandService.PAYMENT_EVENT_TOPIC, req, payload,
        e -> result(e, "HELD", null));
  }

  /** WAL-07: CommitHold, under the hold's txnId; an unknown hold is 422 {@code MW-CORE-HOLD_NOT_FOUND}. */
  @Transactional(noRollbackFor = MwException.class)
  public OperationResult commit(String holdId, HoldActionRequest req) {
    Hold hold = holdOf(holdId);
    return run(hold.txnId(), round("commit", req.round()), "CommitHold", CoreCommandService.PAYMENT_EVENT_TOPIC, req, holdPayload(holdId, req),
        e -> result(e, "COMMITTED", null));
  }

  /** WAL-08: ReleaseHold, under the hold's txnId. */
  @Transactional(noRollbackFor = MwException.class)
  public OperationResult release(String holdId, HoldActionRequest req) {
    Hold hold = holdOf(holdId);
    return run(hold.txnId(), round("release", req.round()), "ReleaseHold", CoreCommandService.PAYMENT_EVENT_TOPIC, req, holdPayload(holdId, req),
        e -> result(e, "RELEASED", null));
  }

  /** WAL-09: PostTransfer. */
  @Transactional(noRollbackFor = MwException.class)
  public OperationResult transfer(WalletTransferRequest req, String assertion) {
    return run(req.txnId(), "transfer", "PostTransfer", CoreCommandService.EVENT_TOPIC, req, transferPayload(req, assertion),
        e -> result(e, "POSTED", null));
  }

  /** WAL-10: CreditWallet from the named settlement wallet, else SETTLEMENT (no assertion, no fee). */
  @Transactional(noRollbackFor = MwException.class)
  public OperationResult credit(WalletCreditRequest req) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("txnType", req.txnType());
    p.put("creditParty", req.creditParty());
    p.put("amount", req.amount());
    if (req.settlementWallet() != null) {
      p.put("attributes", Map.of(TransferPayload.SETTLEMENT_WALLET, req.settlementWallet()));
    }
    return run(req.txnId(), round("credit", req.round()), "CreditWallet", CoreCommandService.EVENT_TOPIC, req, p, e -> result(e, "CREDITED", null));
  }

  /**
   * WAL-11: ReverseTransaction. Without {@code amount} the whole original (its transfer debits); the
   * command's rules then hold as on Kafka (a smaller amount is a partial reversal, ticket 116).
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
   * UnlockWallet over REST (ticket 95b; mw-admin-api after maker-checker): a core LOCKED wallet back to
   * ACTIVE, else 422 {@code MW-CORE-WALLET_NOT_LOCKED} / {@code WALLET_NOT_FOUND}; idempotent under
   * {@code <txnId>:rest:unlock}.
   */
  @Transactional(noRollbackFor = MwException.class)
  public AccountActionResult unlock(String walletId, AccountUnlockRequest req) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("subject", Map.of("type", "WALLET", "id", walletId));
    p.put("changeRequestId", req.changeRequestId());
    p.put("makerId", req.makerId());
    p.put("checkerId", req.checkerId());
    return run(req.txnId(), "unlock", "UnlockWallet", CoreCommandService.EVENT_TOPIC,
        new java.util.TreeMap<>(Map.of("walletId", walletId, "request", req)), p,
        e -> new AccountActionResult(e.txnId(), payload(e).path("walletId").asString(), "UNLOCKED", e.coreRef(), null));
  }

  /**
   * VerifyCustomerFactors over REST (ticket 95b; mw-auth's self-service PIN reset): {@code VERIFIED}, else
   * 422 {@code MW-CORE-WALLET_NOT_FOUND} / {@code WALLET_NOT_ACTIVE} / an injected {@code FACTORS_MISMATCH};
   * idempotent under {@code <txnId>:rest:factors}. The factors are hashes, never logged.
   */
  @Transactional(noRollbackFor = MwException.class)
  public AccountActionResult verifyFactors(String walletId, FactorsRequest req) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("subject", Map.of("type", "WALLET", "id", walletId));
    p.put("resetId", req.resetId());
    p.put("factors", req.factors());
    return run(req.txnId(), "factors", "VerifyCustomerFactors", CoreCommandService.EVENT_TOPIC,
        new java.util.TreeMap<>(Map.of("walletId", walletId, "request", req)), p,
        e -> new AccountActionResult(e.txnId(), payload(e).path("walletId").asString(), "VERIFIED", e.coreRef(),
            payload(e).path("resetId").asString()));
  }

  /** The operation of an operator's round {@code n >= 1}: {@code <op>:manual-<n>}, else {@code op}. */
  static String round(String op, Integer round) {
    return round == null || round <= 0 ? op : op + ":manual-" + round;
  }

  /**
   * One money operation: under the operation's lock, the body hash checked (409 on another body) or
   * recorded, then the command through {@link CoreCommandService#handle(CoreCommand, String)}
   * (answered from {@code command_log} on a repeat), its result event turned into the answer or the
   * refusal.
   */
  private <T> T run(String txnId, String op, String commandType, String eventTopic, Object request,
      Map<String, Object> payload, Function<CoreEvent, T> answer) {
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
    return sha256(json.writeValueAsString(request));
  }

  /** Hex SHA-256 of the UTF-8 text (the assertion itself is never stored). */
  private static String sha256(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static EntryView view(PostedEntry e) {
    return new EntryView(e.txnId(), e.walletId(), e.direction(), e.kind(), Amount.bdt(e.amountMinor()),
        e.balanceAfterMinor() == null ? null : Amount.bdt(e.balanceAfterMinor()), e.at());
  }
}
