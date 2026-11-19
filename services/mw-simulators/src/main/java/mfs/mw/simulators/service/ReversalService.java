package mfs.mw.simulators.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import mfs.mw.simulators.dto.SimReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The partner reverse of the simulated OCS, bill aggregator and C2W bank (ticket 97, FRS MCK-02),
 * stateful: a transaction the partner applied ({@code SUCCESS} in its ledger) is reversed once, and
 * every later reverse of it answers that first result again (same {@code reversalRef} and {@code
 * reversalId}), so the saga can resend safely. Unknown transaction 404 {@code NOT_FOUND} ({@code
 * 4040}); one that did not succeed 422 {@code FAILED} {@code 4091}; the partner's scenario {@code
 * outage} 503 (nothing applied). Mock control beyond the outage comes with ticket 102.
 */
@Service
public class ReversalService {

  private static final Logger log = LoggerFactory.getLogger(ReversalService.class);

  private final ScenarioStore scenarios;
  private final OcsLedger ocs;
  private final BillPaymentLedger billagg;
  private final BankDebitLedger bank;
  private final Map<String, Map<String, Object>> reversed = new ConcurrentHashMap<>();
  private final AtomicLong refs = new AtomicLong();

  public ReversalService(ScenarioStore scenarios, OcsLedger ocs, BillPaymentLedger billagg, BankDebitLedger bank) {
    this.scenarios = scenarios;
    this.ocs = ocs;
    this.billagg = billagg;
    this.bank = bank;
  }

  /** Reverses {@code transactionId} of {@code partner} ({@code ocs}, {@code billagg} or {@code c2wbank}). */
  public SimReply reverse(String partner, String transactionId, String reversalId) {
    if (scenarios.get(partner).outage()) {
      return new SimReply(503, answer(transactionId, "UNAVAILABLE", "5030", "partner unavailable", null, null));
    }
    Optional<String> status = status(partner, transactionId);
    if (status.isEmpty()) {
      return new SimReply(404, answer(transactionId, "NOT_FOUND", "4040", "no such transaction", null, null));
    }
    if (!"SUCCESS".equals(status.get())) {
      return new SimReply(422, answer(transactionId, "FAILED", "4091", "transaction cannot be reversed", null, null));
    }
    Map<String, Object> result =
        reversed.computeIfAbsent(
            partner + "/" + transactionId,
            k -> {
              log.atInfo()
                  .addKeyValue("event", "sim.reverse.applied")
                  .addKeyValue("partnerId", partner)
                  .log("reverse of {} applied", transactionId);
              return answer(transactionId, "REVERSED", "0000", "reversed", "REV-" + refs.incrementAndGet(), reversalId);
            });
    return new SimReply(200, result);
  }

  /** How many transactions were reversed (tests). */
  public int reversals() {
    return reversed.size();
  }

  private Optional<String> status(String partner, String transactionId) {
    return switch (partner) {
      case "ocs" -> ocs.find(transactionId).map(TopupRecord::status);
      case "billagg" -> billagg.find(transactionId).map(BillPaymentRecord::status);
      case "c2wbank" -> bank.find(transactionId).map(BankDebitRecord::status);
      default -> Optional.empty();
    };
  }

  private static Map<String, Object> answer(
      String transactionId, String status, String code, String message, String reversalRef, String reversalId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("transactionId", transactionId);
    body.put("status", status);
    body.put("code", code);
    body.put("message", message);
    if (reversalRef != null) {
      body.put("reversalRef", reversalRef);
    }
    if (reversalId != null) {
      body.put("reversalId", reversalId);
    }
    return java.util.Collections.unmodifiableMap(body);
  }
}
