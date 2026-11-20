package mfs.mw.orchestrator.mapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.Balance;
import mfs.mw.orchestrator.dto.CoreReply;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.SagaEvent.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The core's REST answer (ADR D31, ticket 95a; {@code client/CoreWalletClient}) to the saga event of
 * its step, and a refusal's code to the status error {@code MW-CORE-<reason>} (spec 5.3). The answer is
 * named by the core's result name of its operation ({@link #result}: TransferPosted, HoldRejected, ...,
 * the names of the core's own result events), which the saga timeline records. An ambiguous outcome
 * maps to no event: the step's deadline decides (spec 7.2, 7.4).
 */
@Component
public class CoreReplyMapper {

  static final String UNKNOWN_REASON = "UNKNOWN";
  static final String FALLBACK_MESSAGE = "Rejected by the core";
  /** The one non-core reason code: an assertion the core refused, passed through as it is. */
  static final String ASSERTION_REFUSED = "MW-AUTH-046";
  static final String CORE_PREFIX = "MW-CORE-";

  private static final Pattern REASON = Pattern.compile("[A-Z0-9_]+");

  /** The core's result names of each call: success, refusal. */
  private record Results(String success, String refusal) {}

  private static final Map<String, Results> RESULTS = Map.of(
      "ValidateTransaction", new Results("TransactionValidated", "TransactionRejected"),
      "PostTransfer", new Results("TransferPosted", "TransferRejected"),
      "HoldFunds", new Results("FundsHeld", "HoldRejected"),
      "CommitHold", new Results("HoldCommitted", "CommitFailed"),
      "ReleaseHold", new Results("HoldReleased", "ReleaseFailed"),
      "CreditWallet", new Results("WalletCredited", "CreditRejected"),
      "ReverseTransaction", new Results("TransactionReversed", "ReversalRejected"),
      "QueryTransaction", new Results("TransactionStatus", "TransactionStatusRejected"),
      "BalanceInquiry", new Results("BalanceReported", "BalanceInquiryRejected"));

  /** English and Bengali text per core reason (the reasons mw-core-mock gives). */
  private record Text(String en, String bn) {}

  private static final Map<String, Text> TEXTS =
      Map.ofEntries(
          Map.entry("WALLET_NOT_FOUND", new Text("Wallet not found", "ওয়ালেট পাওয়া যায়নি")),
          Map.entry("CREDIT_WALLET_NOT_FOUND", new Text("Receiver wallet not found", "প্রাপকের ওয়ালেট পাওয়া যায়নি")),
          Map.entry("ACCOUNT_BLOCKED", new Text("Account is blocked", "অ্যাকাউন্ট ব্লক করা আছে")),
          Map.entry("CREDIT_ACCOUNT_BLOCKED", new Text("Receiver account is blocked", "প্রাপকের অ্যাকাউন্ট ব্লক করা আছে")),
          Map.entry("SAME_WALLET", new Text("Sender and receiver are the same", "প্রেরক ও প্রাপক একই")),
          Map.entry("INVALID_AMOUNT", new Text("Invalid amount", "অবৈধ পরিমাণ")),
          Map.entry("LIMIT_EXCEEDED_PER_TXN", new Text("Per-transaction limit exceeded", "প্রতি লেনদেনের সীমা অতিক্রম")),
          Map.entry("LIMIT_EXCEEDED_DAILY", new Text("Daily limit exceeded", "দৈনিক সীমা অতিক্রম")),
          Map.entry("INSUFFICIENT_BALANCE", new Text("Insufficient balance", "অপর্যাপ্ত ব্যালেন্স")),
          Map.entry(
              "INVALID_COUNTERPARTY",
              new Text("This wallet cannot be used for this transaction", "এই লেনদেনে এই ওয়ালেট ব্যবহার করা যাবে না")),
          Map.entry(
              "ACCOUNT_LOCKED",
              new Text(
                  "Account is locked, contact customer care",
                  "অ্যাকাউন্ট লক করা আছে, গ্রাহক সেবায় যোগাযোগ করুন")));

  /** The core's result name of the answer; null for an ambiguous outcome or an unknown call. */
  public String result(CoreReply reply) {
    Results r = RESULTS.get(reply.commandType());
    if (r == null || reply.kind() == CoreReply.Kind.AMBIGUOUS) {
      return null;
    }
    return reply.kind() == CoreReply.Kind.ANSWERED ? r.success() : r.refusal();
  }

  /** SUCCESS or FAILURE (null when ambiguous), as the saga timeline records it. */
  public String outcome(CoreReply reply) {
    return switch (reply.kind()) {
      case ANSWERED -> "SUCCESS";
      case REFUSED -> "FAILURE";
      case AMBIGUOUS -> null;
    };
  }

  /**
   * The saga event of the answer; empty when ambiguous (the deadline decides) or for a call no wired
   * flow uses.
   *
   * @param needsHold the flow holds funds after validation
   * @param callPartner the flow takes inbound money (ADD_MONEY_BANK): validation is followed by the
   *     partner call, and the credit answers its commit step
   */
  public Optional<SagaEvent> toSagaEvent(CoreReply reply, boolean needsHold, boolean callPartner) {
    String result = result(reply);
    return Optional.ofNullable(result == null ? null : switch (result) {
      case "TransactionValidated" -> new Validated(needsHold, callPartner);
      case "TransactionRejected" -> new ValidationRejected();
      case "TransferPosted" -> new PostSucceeded();
      case "TransferRejected" -> new PostRejected();
      case "FundsHeld" -> new HoldPlaced();
      case "HoldRejected" -> new HoldRejected();
      case "HoldCommitted", "WalletCredited" -> new CommitAcknowledged();
      // a definite refusal of a commit, release or credit: an operator decides (spec 7.4 conflict)
      case "CommitFailed", "CreditRejected" -> new CommitRefused();
      case "HoldReleased" -> new ReleaseAcknowledged();
      case "ReleaseFailed" -> new ReleaseRefused();
      case "TransactionReversed" -> new ReversalAcknowledged();
      case "ReversalRejected" -> new ReversalFailed();
      case "BalanceReported" -> new BalanceReported();
      case "BalanceInquiryRejected" -> new BalanceInquiryRejected();
      // WAL-13 after a HOLDING timeout (spec 7.2)
      case "TransactionStatus" -> new HoldQueryResult("HELD".equals(txnStatus(reply)));
      default -> null;
    });
  }

  /** The core reason of a refusal: {@code MW-CORE-<reason>} without its prefix, other codes as they are. */
  public String reasonCode(CoreReply reply) {
    String code = reply.code();
    if (code == null) {
      return null;
    }
    return code.startsWith(CORE_PREFIX) ? code.substring(CORE_PREFIX.length()) : code;
  }

  /** {@code coreRef} of the answer, else null. */
  public String coreRef(CoreReply reply) {
    return text(reply, "coreRef");
  }

  /** {@code holdId} of the answer (a hold, WAL-13 of a held txn), else null. */
  public String holdId(CoreReply reply) {
    String id = text(reply, "holdId");
    return id == null || id.isBlank() ? null : id;
  }

  /** {@code fee.valueMinor} of a validation, else null. */
  public Long feeMinor(CoreReply reply) {
    if (reply.kind() != CoreReply.Kind.ANSWERED || reply.body() == null || !reply.body().path("fee").has("valueMinor")) {
      return null;
    }
    return reply.body().path("fee").path("valueMinor").asLong();
  }

  /** WAL-13's {@code status} (NOT_FOUND, HELD, POSTED, ...), else null. */
  public String txnStatus(CoreReply reply) {
    return "QueryTransaction".equals(reply.commandType()) && reply.kind() == CoreReply.Kind.ANSWERED
        ? text(reply, "status") : null;
  }

  /** {@code available} and {@code asOf} of a balance answer (ADR D26), for the COMPLETED status only. */
  public Optional<Balance> balance(CoreReply reply) {
    if (!"BalanceReported".equals(result(reply)) || reply.body() == null) {
      return Optional.empty();
    }
    JsonNode available = reply.body().path("available");
    String asOf = reply.body().path("asOf").asString(null);
    if (!available.path("valueMinor").isIntegralNumber() || asOf == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(new Balance(
          new Amount(available.path("valueMinor").asLong(), available.path("currency").asString(Amount.BDT)),
          Instant.parse(asOf)));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  /** The status error for a refusal: code {@code MW-CORE-<reason>}, not retryable. */
  public ErrorInfo error(CoreReply reply) {
    String reason = reasonCode(reply);
    if (ASSERTION_REFUSED.equals(reason)) {
      // the core refused mw-auth's transaction assertion (spec 18.7, D22; ticket 71): its own code
      return new ErrorInfo(ASSERTION_REFUSED,
          "Transaction authorisation invalid or already used; enter your PIN again",
          "লেনদেনের অনুমোদন অবৈধ বা আগেই ব্যবহৃত; আবার পিন দিন", false);
    }
    if (reason == null || !REASON.matcher(reason).matches()) {
      reason = UNKNOWN_REASON;
    }
    Text text = TEXTS.get(reason);
    return new ErrorInfo(
        CORE_PREFIX + reason,
        text != null ? text.en() : FALLBACK_MESSAGE,
        text != null ? text.bn() : null,
        false);
  }

  private static String text(CoreReply reply, String field) {
    return reply.body() == null ? null : reply.body().path(field).asString(null);
  }
}
