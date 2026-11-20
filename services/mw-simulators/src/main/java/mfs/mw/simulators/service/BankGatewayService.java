package mfs.mw.simulators.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import mfs.mw.simulators.dto.BankProfile;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MockAbortException;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The simulated bank gateway (ticket 117d, FRS EXT-12, MCK-02/03): every bank of {@code
 * seed/v1/bank-profiles.csv}, the wire format of {@code deploy/partner-routes/bank/generate.mjs}. Mock
 * party {@code bank-<code>}, operations {@code verify}, {@code transfer}, {@code status}, {@code reverse},
 * {@code health}; keys {@code account}, {@code txnRef}. Replies are protocol-neutral {@link SimReply}s
 * (REST body maps, or a cut-short string); the SOAP front end renders them as envelopes.
 *
 * <p>Triggers (account number ending, MCK-03; the trigger table is in this module's CLAUDE.md): {@code 00}
 * no such account 4001, {@code 98} dormant 4002 (also a seeded DORMANT / CLOSED account), {@code 99}
 * insufficient funds 4003 on a DEBIT (also a balance short of the amount), {@code 97} limit exceeded 4004,
 * {@code 96} transfer refused 4005, {@code 95} answered 202 PENDING (outcome SUCCESS by callback); amounts
 * as {@link MagicAmounts} ({@code .13} timeout applied, {@code .77} 500 applied, {@code .99} FAILED {@code
 * 9876}, {@code .55} cut-short body applied). A DEBIT (C2W) is always answered 202 PENDING, its outcome
 * by callback; a CREDIT (W2B) 200 SUCCESS. A repeated transactionId answers the stored outcome.
 */
@Service
public class BankGatewayService {

  public static final String NOT_FOUND = "4001";
  public static final String INACTIVE = "4002";
  public static final String INSUFFICIENT_FUNDS = "4003";
  public static final String LIMIT_EXCEEDED = "4004";
  public static final String REFUSED = "4005";
  public static final String AMBIGUOUS = "9876";

  private static final Logger log = LoggerFactory.getLogger(BankGatewayService.class);
  private static final Pattern ACCOUNT = Pattern.compile("[0-9]{6,20}");
  private static final List<BankProfile> PROFILES = SeedFiles.bankProfiles(SeedFiles.VERSION);
  private static final Map<String, String> MESSAGES = Map.of(
      NOT_FOUND, "No such account", INACTIVE, "Account inactive", INSUFFICIENT_FUNDS, "Insufficient funds",
      LIMIT_EXCEEDED, "Limit exceeded", REFUSED, "Transfer refused", AMBIGUOUS, "Unknown error");

  private final MockControl control;
  private final BankGatewayLedger ledger;
  private final CallbackService callbacks;
  private final JsonMapper json;

  public BankGatewayService(MockControl control, BankGatewayLedger ledger, CallbackService callbacks, JsonMapper json) {
    this.control = control;
    this.ledger = ledger;
    this.callbacks = callbacks;
    this.json = json;
  }

  /** The banks served. */
  public static List<BankProfile> profiles() {
    return PROFILES;
  }

  /** The bank with this code on this protocol, else 404 (unknown bank, or the other protocol's endpoint). */
  public BankProfile profile(String code, BankProfile.Protocol protocol) {
    return PROFILES.stream()
        .filter(p -> p.code().equals(code) && (protocol == null || p.protocol() == protocol))
        .findFirst()
        .orElseThrow(() -> new MwException(ErrorCodes.VAL_NOT_FOUND, "No such bank endpoint")
            .withStatus(HttpStatus.NOT_FOUND));
  }

  /**
   * Whether a request carries the profile's auth (the simulator holds none of the adapter's generated
   * secrets, so it checks the scheme is present, which proves the route picked the right one): REST
   * hmac-sha256 {@code X-Signature} and {@code X-Timestamp}, api-key {@code X-Api-Key}; SOAP basic {@code
   * Authorization: Basic}; ws-security is checked on the envelope (and none on the health probe).
   */
  public static boolean httpAuthPresent(BankProfile p, UnaryOperator<String> header) {
    return switch (p.auth()) {
      case "hmac-sha256" -> present(header.apply("X-Signature")) && present(header.apply("X-Timestamp"));
      case "api-key" -> present(header.apply("X-Api-Key"));
      case "basic" -> {
        String a = header.apply("Authorization");
        yield a != null && a.regionMatches(true, 0, "Basic ", 0, 6) && a.length() > 6;
      }
      default -> true;
    };
  }

  public static SimReply unauthenticated() {
    return new SimReply(401, body("status", "FAILED", "code", "1002", "message", "authentication failed"));
  }

  public SimReply health(BankProfile p) {
    Decision d = control.decide(p.party(), "health", Map.of(), SimMode.SUCCESS);
    return MockCalls.answer(d, p.party(), () -> new SimReply(200, body("status", "UP", "bank", p.code())),
        BankGatewayService::unavailable, BankGatewayService::internalError, code -> refusal(null, code));
  }

  /** AVL: the holder name of an active account, or 422 4001 / 4002. */
  public SimReply verify(BankProfile p, String accountNumber) {
    if (accountNumber == null || !ACCOUNT.matcher(accountNumber).matches()) {
      return invalid("accountNumber");
    }
    Decision d = control.decide(p.party(), "verify", Map.of("account", accountNumber), SimMode.SUCCESS);
    return MockCalls.answer(d, p.party(), () -> {
      Optional<BankGatewayLedger.Account> account = ledger.account(p.code(), accountNumber);
      if (account.isEmpty()) {
        return refusal(null, NOT_FOUND);
      }
      if (!"ACTIVE".equals(account.get().status())) {
        return refusal(null, INACTIVE);
      }
      return new SimReply(200, body("status", "ACTIVE", "accountName", account.get().holderName(),
          "verifyRef", p.code().toUpperCase(Locale.ROOT) + "-AVL-" + ledger.nextRef(p.code())));
    }, BankGatewayService::unavailable, BankGatewayService::internalError, code -> refusal(null, code));
  }

  /** A DEBIT (C2W) or CREDIT (W2B) of an account: applied once per transactionId. */
  public SimReply transfer(BankProfile p, String transactionId, String accountNumber, String amount, String direction) {
    BigDecimal value = amount(amount);
    if (transactionId == null || transactionId.isBlank() || transactionId.length() > 64) {
      return invalid("transactionId");
    }
    if (accountNumber == null || !ACCOUNT.matcher(accountNumber).matches()) {
      return invalid("accountNumber");
    }
    if (value == null) {
      return invalid("amount");
    }
    if (!"DEBIT".equals(direction) && !BankTransfer.CREDIT.equals(direction)) {
      return invalid("direction");
    }
    Decision d = control.decide(p.party(), "transfer", Map.of("account", accountNumber, "txnRef", transactionId),
        magic(accountNumber, amount, direction));
    Optional<SimReply> closed = MockCalls.admit(d, p.party(), BankGatewayService::unavailable);
    if (closed.isPresent()) {
      return closed.get();
    }
    // M9 review: check and apply in one atomic step, so concurrent resends never credit twice
    BankGatewayLedger.Applied applied =
        ledger.applyOnce(p.code(), transactionId, () -> apply(p, d, transactionId, accountNumber, value, direction));
    if (!applied.fresh()) {
      return reply(applied.transfer(), d);
    }
    BankTransfer record = applied.transfer();
    log.atInfo()
        .addKeyValue("event", "sim.bank.transfer.applied")
        .addKeyValue("partnerId", p.party())
        .log("transfer {} {} status {} code {} ({})", record.transactionId(), record.direction(), record.status(),
            record.code(), d.source());
    if (record.mode() == SimMode.TIMEOUT) {
      MockCalls.pause(d.holdMs());
    } else {
      callbacks.afterBankTransfer(p.party(), d, json.writeValueAsString(record.body()),
          json.writeValueAsString(record.pendingBody()));
    }
    if (record.mode() == SimMode.CONNECTION_RESET) {
      throw new MockAbortException(p.party());
    }
    return reply(record, d);
  }

  /** The true outcome of an earlier transfer, or 404 NOT_FOUND. */
  public SimReply status(BankProfile p, String transactionId) {
    Decision d = control.decide(p.party(), "status", Map.of("txnRef", transactionId), SimMode.SUCCESS);
    return MockCalls.answer(d, p.party(),
        () -> ledger.find(p.code(), transactionId)
            .map(t -> new SimReply(200, t.body()))
            .orElseGet(() -> new SimReply(404, body("transactionId", transactionId, "status", "NOT_FOUND",
                "code", "4040", "message", "No such transfer"))),
        BankGatewayService::unavailable, BankGatewayService::internalError, code -> refusal(transactionId, code));
  }

  /** Undoes a successful transfer once; a later reverse answers the first result again. */
  public SimReply reverse(BankProfile p, String transactionId, String reversalId) {
    Decision d = control.decide(p.party(), "reverse", Map.of("txnRef", transactionId), SimMode.SUCCESS);
    return MockCalls.answer(d, p.party(), () -> {
      Optional<BankTransfer> t = ledger.find(p.code(), transactionId);
      if (t.isEmpty()) {
        return new SimReply(404, body("transactionId", transactionId, "status", "NOT_FOUND", "code", "4040",
            "message", "No such transfer"));
      }
      if (!t.get().succeeded()) {
        return new SimReply(422, body("transactionId", transactionId, "status", "FAILED", "code", "4091",
            "message", "Transfer not reversible"));
      }
      return new SimReply(200, ledger.reversal(p.code(), transactionId, () -> {
        BankGatewayLedger.Account account = ledger.account(p.code(), t.get().accountNumber()).orElseThrow();
        if (BankTransfer.CREDIT.equals(t.get().direction())) {
          account.debit(t.get().amount()); // a reversal is never refused for the balance
        } else {
          account.credit(t.get().amount());
        }
        return body("transactionId", transactionId, "status", "REVERSED", "code", "000", "message", "Reversed",
            "reversalRef", ledger.nextRef(p.code()) + "-R", "reversalId", reversalId);
      }));
    }, BankGatewayService::unavailable, BankGatewayService::internalError, code -> refusal(transactionId, code));
  }

  /** An account's state for tests ({@code GET /sim/bank/{code}/accounts/{account}}). */
  public Map<String, Object> account(String code, String accountNumber) {
    BankProfile p = profile(code, null);
    BankGatewayLedger.Account a = ledger.account(p.code(), accountNumber)
        .orElseThrow(() -> new MwException(ErrorCodes.VAL_NOT_FOUND, "No such account").withStatus(HttpStatus.NOT_FOUND));
    return body("bank", p.code(), "accountNumber", accountNumber, "holderName", a.holderName(), "status", a.status(),
        "balance", a.balance().toPlainString());
  }

  private BankTransfer apply(BankProfile p, Decision d, String id, String accountNumber, BigDecimal amount,
      String direction) {
    String code = switch (d.mode()) {
      case BUSINESS_FAILURE -> d.codeOr(trigger(accountNumber, direction));
      case UNKNOWN_CODE -> d.codeOr(AMBIGUOUS);
      default -> null;
    };
    if (code == null) {
      Optional<BankGatewayLedger.Account> account = ledger.account(p.code(), accountNumber);
      if (account.isEmpty()) {
        code = NOT_FOUND;
      } else if (!"ACTIVE".equals(account.get().status())) {
        code = INACTIVE;
      } else if (BankTransfer.CREDIT.equals(direction)) {
        account.get().credit(amount);
      } else if (!account.get().debit(amount)) {
        code = INSUFFICIENT_FUNDS;
      }
    }
    boolean ok = code == null;
    boolean pending = ok && (!BankTransfer.CREDIT.equals(direction) || accountNumber.endsWith("95")
        || control.settings(p.party()).callbackOnly());
    return new BankTransfer(p.code(), id, accountNumber, amount, direction,
        ok ? BankTransfer.SUCCESS : BankTransfer.FAILED, ok ? "000" : code,
        ok ? "Transfer completed" : MESSAGES.getOrDefault(code, "Refused"), ledger.nextRef(p.code()), d.mode(), pending);
  }

  private static SimMode magic(String accountNumber, String amount, String direction) {
    return trigger(accountNumber, direction) != null ? SimMode.BUSINESS_FAILURE : MagicAmounts.mode(amount);
  }

  /** The account-ending refusal of a transfer, or null. */
  private static String trigger(String accountNumber, String direction) {
    if (accountNumber.endsWith("99") && !BankTransfer.CREDIT.equals(direction)) {
      return INSUFFICIENT_FUNDS;
    }
    if (accountNumber.endsWith("97")) {
      return LIMIT_EXCEEDED;
    }
    return accountNumber.endsWith("96") ? REFUSED : null;
  }

  private static SimReply reply(BankTransfer t, Decision d) {
    return switch (t.mode()) {
      case SERVER_ERROR_AFTER_SEND -> new SimReply(d.statusOr(500), internalError().body());
      case MALFORMED -> new SimReply(t.pending() ? 202 : 200, MockCalls.CUT_SHORT);
      default -> t.succeeded()
          ? (t.pending() ? new SimReply(202, t.pendingBody()) : new SimReply(200, t.body()))
          : new SimReply(422, t.body());
    };
  }

  private static BigDecimal amount(String amount) {
    try {
      BigDecimal v = amount == null ? null : new BigDecimal(amount);
      return v != null && v.signum() > 0 && v.scale() <= 2 ? v : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static SimReply invalid(String field) {
    return new SimReply(400, body("status", "FAILED", "code", "1000", "message", "invalid " + field));
  }

  private static SimReply refusal(String transactionId, String code) {
    return new SimReply(422, body("transactionId", transactionId, "status", "FAILED", "code", code,
        "message", MESSAGES.getOrDefault(code, "Refused")));
  }

  static SimReply unavailable() {
    return new SimReply(503, body("status", "UNAVAILABLE", "code", "5030", "message", "Service unavailable"));
  }

  static SimReply internalError() {
    return new SimReply(500, body("status", "ERROR", "code", "5000", "message", "Internal error"));
  }

  private static boolean present(String v) {
    return v != null && !v.isBlank();
  }

  /** A body map of name/value pairs; null values are left out. */
  static Map<String, Object> body(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      if (kv[i + 1] != null) {
        m.put((String) kv[i], kv[i + 1]);
      }
    }
    return m;
  }
}
