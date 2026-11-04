package mfs.mw.orchestrator.mapper;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.SagaEvent.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Core event to saga event, and core reason code to {@code MW-CORE-*} error (spec 5.3). The error
 * code is {@code MW-CORE-<reasonCode>}, which matches the status contract's code pattern.
 */
@Component
public class CoreEventMapper {

  static final String UNKNOWN_REASON = "UNKNOWN";
  static final String FALLBACK_MESSAGE = "Rejected by the core";

  private static final Pattern REASON = Pattern.compile("[A-Z0-9_]+");

  /** English and Bengali text per core reason (the reasons mw-core-mock emits). */
  private record Text(String en, String bn) {}

  private static final Map<String, Text> TEXTS =
      Map.ofEntries(
          Map.entry("WALLET_NOT_FOUND", new Text("Wallet not found", "ওয়ালেট পাওয়া যায়নি")),
          Map.entry("CREDIT_WALLET_NOT_FOUND", new Text("Receiver wallet not found", "প্রাপকের ওয়ালেট পাওয়া যায়নি")),
          Map.entry("ACCOUNT_BLOCKED", new Text("Account is blocked", "অ্যাকাউন্ট ব্লক করা আছে")),
          Map.entry("CREDIT_ACCOUNT_BLOCKED", new Text("Receiver account is blocked", "প্রাপকের অ্যাকাউন্ট ব্লক করা আছে")),
          Map.entry("SAME_WALLET", new Text("Sender and receiver are the same", "প্রেরক ও প্রাপক একই")),
          Map.entry("INVALID_PIN", new Text("Invalid PIN", "ভুল পিন")),
          Map.entry("INVALID_PIN_BLOCK", new Text("PIN could not be read", "পিন পড়া যায়নি")),
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
                  "Account is locked after too many wrong PINs",
                  "বারবার ভুল পিন দেওয়ায় অ্যাকাউন্ট লক করা হয়েছে")));

  /**
   * The saga event for a core result; empty for event types no wired flow consumes yet.
   *
   * @param needsHold whether the transaction's flow holds funds after validation
   */
  public Optional<SagaEvent> toSagaEvent(CoreEvent event, boolean needsHold) {
    String type = event.eventType() == null ? "" : event.eventType();
    return Optional.ofNullable(
        switch (type) {
          case "TransactionValidated" -> new Validated(needsHold);
          case "TransactionRejected" -> new ValidationRejected();
          case "TransferPosted" -> new PostSucceeded();
          case "TransferRejected" -> new PostRejected();
          case "FundsHeld" -> new HoldPlaced();
          case "HoldRejected" -> new HoldRejected();
          case "HoldCommitted" -> new CommitAcknowledged();
          case "HoldReleased" -> new ReleaseAcknowledged();
          case "TransactionReversed" -> new ReversalAcknowledged();
          case "ReversalRejected" -> new ReversalFailed();
          default -> null;
        });
  }

  /** {@code payload.fee.valueMinor} of a TransactionValidated event, else null. */
  public Long feeMinor(CoreEvent event) {
    JsonNode fee = event.payload() == null ? null : event.payload().path("fee");
    return fee == null || !fee.has("valueMinor") ? null : fee.path("valueMinor").asLong();
  }

  /** The status error for a failure event: code {@code MW-CORE-<reason>}, not retryable. */
  public ErrorInfo error(CoreEvent event) {
    String reason = event.reasonCode();
    if (reason == null || !REASON.matcher(reason).matches()) {
      reason = UNKNOWN_REASON;
    }
    Text text = TEXTS.get(reason);
    return new ErrorInfo(
        "MW-CORE-" + reason,
        text != null ? text.en() : FALLBACK_MESSAGE,
        text != null ? text.bn() : null,
        false);
  }
}
