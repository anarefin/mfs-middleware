package mfs.mw.data.export.service;

import jakarta.validation.ValidationException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.data.export.dto.NotifyStatusEvent;
import mfs.mw.data.export.dto.TxnStatusEvent;
import org.springframework.stereotype.Component;

/**
 * Builds the DW events (ticket 128). Every map goes through {@link PiiPolicy} for its topic, so a
 * field the policy does not tag never leaves the method. Dates are ISO-8601 strings.
 */
@Component
public class DwEvents {

  /** One event ready for the outbox. */
  public record Outbound(String topic, String key, Map<String, Object> body) {}

  private final PiiPolicy pii;

  public DwEvents(PiiPolicy pii) {
    this.pii = pii;
  }

  /** State-changed always; created only for RECEIVED; completed only for COMPLETED. */
  public List<Outbound> fromTxn(TxnStatusEvent event) {
    require(event.txnId(), "txnId");
    require(event.status(), "status");
    require(event.requestedAt(), "requestedAt");
    require(event.updatedAt(), "updatedAt");
    if (event.amount() == null) {
      throw new ValidationException("txn status without an amount");
    }
    String payer = partyId(event.debitParty(), "debitParty");
    String payee = partyId(event.creditParty(), "creditParty");
    List<Outbound> out = new ArrayList<>();
    out.add(new Outbound(DwTopics.TXN_STATE, event.txnId(), pii.apply(DwTopics.TXN_STATE, txn(event, payer, payee, true))));
    if ("COMPLETED".equals(event.status())) {
      out.add(new Outbound(DwTopics.TXN_COMPLETED, event.txnId(),
          pii.apply(DwTopics.TXN_COMPLETED, txn(event, payer, payee, false))));
    }
    if ("RECEIVED".equals(event.status())) {
      out.add(new Outbound(DwTopics.TXN_CREATED, event.txnId(),
          pii.apply(DwTopics.TXN_CREATED, txn(event, payer, payee, false))));
    }
    return out;
  }

  /** The notification projection. A recipient, when the source carries one, is tokenised. */
  public Outbound fromNotify(NotifyStatusEvent event) {
    require(event.messageId(), "messageId");
    require(event.at(), "at");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", 1);
    body.put("notificationId", event.messageId());
    body.put("correlationId", event.correlationId());
    body.put("channel", event.channel());
    body.put("template", event.templateCode());
    body.put("status", event.status());
    body.put("recipientToken", blankToNull(event.recipient()));
    body.put("sentAt", event.at().toString());
    return new Outbound(DwTopics.NOTIFICATION, event.messageId(), pii.apply(DwTopics.NOTIFICATION, body));
  }

  /** A reconciliation exception. The source schema arrives with ticket 127; these are its facts. */
  public Outbound fromRecon(Map<String, Object> fields) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", 1);
    body.put("exceptionId", text(fields, "exceptionId"));
    body.put("exceptionClass", firstText(fields, "exceptionClass", "class"));
    body.put("partner", firstText(fields, "partner", "partnerId"));
    body.put("txnId", text(fields, "txnId"));
    body.put("amountMinor", fields.get("amountMinor"));
    body.put("statementRef", text(fields, "statementRef"));
    body.put("detectedAt", text(fields, "detectedAt"));
    body.put("correlationId", text(fields, "correlationId"));
    return new Outbound(DwTopics.RECON, String.valueOf(body.get("exceptionId")), pii.apply(DwTopics.RECON, body));
  }

  /** A completed KYC. The source schema arrives with ticket 124. The wallet id is tokenised. */
  public Outbound fromKyc(Map<String, Object> fields) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", 1);
    body.put("walletToken", firstText(fields, "walletId", "walletToken"));
    body.put("kycLevel", text(fields, "kycLevel"));
    body.put("completedAt", text(fields, "completedAt"));
    body.put("correlationId", text(fields, "correlationId"));
    return new Outbound(DwTopics.KYC, String.valueOf(body.get("correlationId")), pii.apply(DwTopics.KYC, body));
  }

  /**
   * An operator action already recorded as {@code mw.audit.event.v1}, projected for the warehouse.
   * Only {@code actor.type = user}. The username stays clear. Before and after are not copied.
   */
  public Outbound fromOperatorAudit(String auditId, String correlationId, String actorId, String action,
      String resource, String outcome, Instant at) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", 1);
    body.put("auditId", auditId);
    body.put("correlationId", correlationId);
    body.put("actorType", "user");
    body.put("actorId", actorId);
    body.put("action", action);
    body.put("resource", resource);
    body.put("outcome", outcome);
    body.put("at", at.toString());
    body.put("service", "mw-data-export");
    return new Outbound(DwTopics.OPERATOR, auditId, pii.apply(DwTopics.OPERATOR, body));
  }

  /** The audit event this service writes when an operator forces a day to be exported again. */
  public Map<String, Object> replayAudit(String operatorId, LocalDate date, Instant at, String auditId,
      String correlationId) {
    Map<String, Object> actor = new LinkedHashMap<>();
    actor.put("type", "user");
    actor.put("id", operatorId);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", 1);
    body.put("auditId", auditId);
    body.put("correlationId", correlationId);
    body.put("actor", actor);
    body.put("action", "dw.export.replayed");
    body.put("resource", "export/" + date);
    body.put("outcome", "SUCCESS");
    body.put("at", at.toString());
    body.put("service", "mw-data-export");
    return body;
  }

  public static String newId() {
    return UUID.randomUUID().toString();
  }

  private static Map<String, Object> txn(TxnStatusEvent event, String payer, String payee, boolean withPrevious) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", 1);
    body.put("txnId", event.txnId());
    body.put("correlationId", event.correlationId());
    body.put("channel", event.channel());
    body.put("txnType", event.txnType());
    body.put("status", event.status());
    if (withPrevious) {
      body.put("previousStatus", event.previousStatus());
    }
    body.put("amountMinor", event.amount().valueMinor());
    body.put("currency", event.amount().currency());
    body.put("payerToken", payer);
    body.put("payeeToken", payee);
    body.put("requestedAt", event.requestedAt().toString());
    body.put("updatedAt", event.updatedAt().toString());
    if ("COMPLETED".equals(event.status())) {
      Instant completed = event.completedAt() != null ? event.completedAt() : event.updatedAt();
      body.put("completedAt", completed.toString());
    } else if (withPrevious && event.completedAt() != null) {
      body.put("completedAt", event.completedAt().toString());
    }
    return body;
  }

  private static String partyId(TxnStatusEvent.Party party, String name) {
    if (party == null || party.id() == null || party.id().isBlank()) {
      throw new ValidationException(name + " is required");
    }
    return party.id();
  }

  private static void require(Object value, String name) {
    if (value == null || (value instanceof String s && s.isBlank())) {
      throw new ValidationException(name + " is required");
    }
  }

  private static String text(Map<String, Object> fields, String key) {
    Object value = fields.get(key);
    if (value == null || String.valueOf(value).isBlank()) {
      throw new ValidationException(key + " is required");
    }
    return String.valueOf(value);
  }

  private static String firstText(Map<String, Object> fields, String... keys) {
    for (String key : keys) {
      Object value = fields.get(key);
      if (value != null && !String.valueOf(value).isBlank()) {
        return String.valueOf(value);
      }
    }
    throw new ValidationException(keys[0] + " is required");
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
