package mfs.mw.recon.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mfs.mw.recon.config.ReconProperties;
import mfs.mw.recon.dto.CoreLedgerEvent;
import mfs.mw.recon.dto.ExternalLine;
import mfs.mw.recon.dto.Finding;
import mfs.mw.recon.dto.MatchRule;
import mfs.mw.recon.dto.PartnerReconEvent;
import mfs.mw.recon.dto.ReconClass;
import mfs.mw.recon.dto.RunSummary;
import mfs.mw.recon.dto.StatementLine;
import mfs.mw.recon.dto.TxnProjection;
import mfs.mw.recon.dto.TxnStatusEvent;
import mfs.mw.recon.exception.ErrorCodes;
import mfs.mw.recon.exception.MwException;
import mfs.mw.recon.messaging.EventMetadata;
import mfs.mw.recon.messaging.Inbox;
import mfs.mw.recon.messaging.OutboxWriter;
import mfs.mw.recon.repository.ReconStore;
import mfs.mw.recon.repository.ReconStore.FileHead;
import mfs.mw.recon.util.Digests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingest, projection and a run (ticket 126). Reports only: the outbox carries
 * {@code mw.recon.result.v1} and, on an operator reprocess, {@code mw.audit.event.v1}. No command
 * topic and no core write.
 */
@Service
public class ReconService {

  private static final Logger log = LoggerFactory.getLogger(ReconService.class);
  private static final Set<String> POSTINGS = Set.of(
      "TransferPosted", "WalletCredited", "HoldCommitted", "TransactionReversed");
  /** Statuses a transaction rests in; an action that leaves its transaction in another one has failed. */
  static final Set<String> FINAL_STATUSES = Set.of("COMPLETED", "FAILED", "REJECTED", "REVERSED", "MANUAL_REVIEW");
  static final String PARTNER = "PARTNER";
  static final String MT940 = "TRUST_MT940";
  static final String MT942 = "TRUST_MT942";

  private final ReconStore store;
  private final MatchingService matching;
  private final Mt940Parser parser;
  private final Inbox inbox;
  private final OutboxWriter outbox;
  private final ReconProperties props;
  private final java.time.Clock clock;
  private final ExceptionEvents events;

  public ReconService(ReconStore store, MatchingService matching, Mt940Parser parser, Inbox inbox, OutboxWriter outbox,
      ReconProperties props, java.time.Clock clock, ExceptionEvents events) {
    this.events = events;
    this.store = store;
    this.matching = matching;
    this.parser = parser;
    this.inbox = inbox;
    this.outbox = outbox;
    this.props = props;
    this.clock = clock;
  }

  /** A redelivered line (same idempotency key) changes nothing. */
  public boolean onPartnerLine(PartnerReconEvent event, String eventId) {
    return inbox.processOnce("partner-recon", eventId, () -> storePartner(event));
  }

  /** A redelivered status event changes nothing; a newer {@code updatedAt} replaces the projection. */
  public boolean onTxn(TxnStatusEvent event, String eventId) {
    return inbox.processOnce("txn-status", eventId, () -> storeTxn(event));
  }

  /** A redelivered core event (same eventId) changes nothing. Non-postings are ignored. */
  public boolean onLedger(CoreLedgerEvent event) {
    if (event.eventId() == null || event.txnId() == null || event.occurredAt() == null
        || !POSTINGS.contains(event.eventType()) || !"SUCCESS".equals(event.outcome())) {
      return false;
    }
    return inbox.processOnce("ledger", event.eventId(), () -> store.insertPosting(
        UUID.fromString(event.eventId()), UUID.fromString(event.txnId()), event.eventType(),
        event.coreRef(), event.occurredAt()));
  }

  /** The same filename and checksum is skipped (REC-08). A new checksum is a new file. */
  @Transactional
  public RunSummary ingestStatement(String filename, byte[] body, boolean daily) {
    String source = daily ? MT940 : MT942;
    String sha = Digests.sha256(body);
    FileHead file = store.register(UUID.randomUUID(), source, filename, sha, body.length, clock.instant(),
        props.trust().partnerId());
    if (!"RECEIVED".equals(file.status())) {
      return RunSummary.skipped(file.id().toString(), file.partnerId(), source, filename);
    }
    List<StatementLine> parsed = daily ? parser.parseMt940(new String(body, java.nio.charset.StandardCharsets.UTF_8))
        : parser.parseMt942(new String(body, java.nio.charset.StandardCharsets.UTF_8));
    store.addStatementLines(file.id(), parsed);
    if (daily) {
      Mt940Parser.Closing closing = parser.closing(new String(body, java.nio.charset.StandardCharsets.UTF_8));
      if (closing != null) {
        store.setClosingBalance(file.id(), closing.minor(), closing.date());
      }
    }
    return finish(file, source, false);
  }

  /** An operator action: the file runs again and the action is audited. Previous open rows are replaced. */
  @Transactional
  public RunSummary reprocess(UUID fileId, String operatorId) {
    if (operatorId == null || operatorId.isBlank()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "operator id is required");
    }
    FileHead file = store.require(fileId);
    if (file == null) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "no such file").withStatus(HttpStatus.NOT_FOUND);
    }
    store.clearResults(fileId);
    RunSummary run = finish(file, file.source(), PARTNER.equals(file.source()));
    outbox.append(ReconTopics.AUDIT, operatorId, meta(run.runId(), "reprocess-" + fileId),
        audit(file, operatorId, run, clock.instant()));
    log.atInfo().addKeyValue("event", "recon.file.reprocessed").addKeyValue("fileId", fileId.toString())
        .log("statement reprocessed");
    return run;
  }

  public int runs(UUID fileId) {
    return store.runs(fileId);
  }

  public int exceptions(UUID fileId) {
    return store.exceptions(fileId);
  }

  public int partnerLines(UUID fileId) {
    return store.partnerLineCount(fileId);
  }

  private void storePartner(PartnerReconEvent event) {
    requireText(event.partnerId(), "partnerId");
    requireText(event.fileName(), "fileName");
    requireText(event.fileId(), "fileId");
    ExternalLine line = partnerLine(event);
    String sha = Digests.sha256(event.fileId());
    long size = sizeOf(event.fileId());
    FileHead file = store.register(UUID.randomUUID(), PARTNER, event.fileName(), sha, size,
        event.receivedAt() == null ? clock.instant() : event.receivedAt(), event.partnerId());
    if ("MATCHED".equals(file.status())) {
      return;
    }
    store.addPartnerLine(file.id(), event.partnerId(), event.family() == null ? "" : event.family(),
        event.lineNumber(), line, currency(event));
    if (event.last()) {
      finish(file, PARTNER, true);
    }
  }

  private void storeTxn(TxnStatusEvent event) {
    if (event.txnId() == null || event.amount() == null || event.updatedAt() == null || event.status() == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "txn status is missing a required field");
    }
    String partnerId = event.partner() == null ? null : event.partner().partnerId();
    UUID txnId = UUID.fromString(event.txnId());
    Instant before = store.txnUpdatedAt(txnId);
    boolean newer = before == null || event.updatedAt().isAfter(before);
    store.upsertTxn(UUID.fromString(event.txnId()), partnerId, event.partnerRef(), event.coreRef(),
        event.amount().valueMinor(), event.amount().currency() == null ? "BDT" : event.amount().currency(),
        event.status(), event.txnType(), event.updatedAt(), event.completedAt());
    resolveAwaiting(txnId, event.status());
    if (newer && FINAL_STATUSES.contains(event.status())) {
      reopenFailed(txnId, event.status());
    }
  }

  /**
   * REC-05 / FRS 10.4 #7: an approved action failed when its transaction reaches, on a newer status event, a final
   * status other than the expected one (a FORCE_COMMIT back in MANUAL_REVIEW or FAILED, a REVERSE the core refused).
   * The exception goes back to OPEN with its {@code mw.recon.exception.v1}, so it can be explained or actioned again
   * instead of staying ACTIONED forever.
   */
  void reopenFailed(UUID txnId, String status) {
    Instant now = clock.instant();
    for (UUID id : store.actionedFailed(txnId, status)) {
      if (store.reopen(id) == 1) {
        events.changed(id, null, now);
        log.atWarn().addKeyValue("event", "recon.exception.action_failed").addKeyValue("exceptionId", id.toString())
            .addKeyValue("txnId", txnId.toString()).addKeyValue("status", status)
            .log("recon exception reopened: the approved action did not reach its expected status");
      }
    }
  }

  /**
   * Ticket 127b: the outcome of an approved action. ACTIONED exceptions of this transaction waiting for this status
   * become RESOLVED, each with its {@code mw.recon.exception.v1}. Called in the caller's transaction.
   */
  void resolveAwaiting(UUID txnId, String status) {
    Instant now = clock.instant();
    for (UUID id : store.actionedAwaiting(txnId, status)) {
      if (store.resolve(id, now) == 1) {
        events.changed(id, null, now);
        log.atInfo().addKeyValue("event", "recon.exception.resolved").addKeyValue("exceptionId", id.toString())
            .addKeyValue("txnId", txnId.toString()).log("recon exception resolved by the transaction outcome");
      }
    }
  }

  private RunSummary finish(FileHead file, String source, boolean raiseMissing) {
    Instant started = clock.instant();
    ZoneId zone = zone();
    List<ExternalLine> lines = PARTNER.equals(source) ? store.partnerLines(file.id()) : store.statementLines(file.id());
    LocalDate businessDate = businessDate(lines, zone);
    List<TxnProjection> txns = candidates(source, file.partnerId(), lines, businessDate, zone);
    Set<String> posted = postedIds(txns);
    List<Finding> findings = matching.match(businessDate, lines, txns, posted, rule(), raiseMissing);
    UUID runId = UUID.randomUUID();
    Instant finished = clock.instant();
    store.insertRun(runId, file.id(), file.partnerId(), source, started, finished, businessDate);
    List<Map<String, Object>> exceptions = new ArrayList<>();
    int matched = 0;
    int missingPartner = 0;
    int missingUpay = 0;
    int amount = 0;
    int status = 0;
    for (Finding finding : findings) {
      UUID exceptionId = UUID.randomUUID();
      store.insertFinding(exceptionId, runId, file.id(), finding, finished);
      switch (finding.classification()) {
        case MATCHED -> matched++;
        case MISSING_IN_PARTNER -> missingPartner++;
        case MISSING_IN_UPAY -> missingUpay++;
        case AMOUNT_MISMATCH -> amount++;
        case STATUS_MISMATCH -> status++;
      }
      if (finding.classification() != ReconClass.MATCHED) {
        exceptions.add(exceptionBody(exceptionId, finding));
        events.changed(exceptionId, null, finished);
      }
    }
    RunSummary summary = new RunSummary(runId.toString(), file.id().toString(), file.partnerId(), source,
        file.filename(), businessDate.toString(), matched, missingPartner, missingUpay, amount, status, false);
    outbox.append(ReconTopics.RESULT, file.partnerId(), meta(runId.toString(), runId.toString()),
        resultBody(summary, exceptions, finished));
    log.atInfo().addKeyValue("event", "recon.run.completed").addKeyValue("partnerId", file.partnerId())
        .addKeyValue("matched", matched).addKeyValue("missingInPartner", missingPartner)
        .addKeyValue("missingInUpay", missingUpay).addKeyValue("amountMismatch", amount)
        .addKeyValue("statusMismatch", status).log("reconciliation run completed");
    return summary;
  }

  private List<TxnProjection> candidates(String source, String partnerId, List<ExternalLine> lines, LocalDate day, ZoneId zone) {
    ZonedDateTime start = day.minusDays(props.match().dateWindowDays() + 1L).atStartOfDay(zone);
    ZonedDateTime end = day.plusDays(props.match().dateWindowDays() + 2L).atStartOfDay(zone);
    if (PARTNER.equals(source)) {
      return store.txnsForPartner(partnerId, start.toInstant(), end.toInstant(), zone);
    }
    Map<String, TxnProjection> byId = new LinkedHashMap<>();
    for (TxnProjection txn : store.txnsInWindow(start.toInstant(), end.toInstant(), zone)) {
      byId.put(txn.txnId(), txn);
    }
    List<UUID> ids = new ArrayList<>();
    for (ExternalLine line : lines) {
      try {
        if (line.reference() != null) {
          ids.add(UUID.fromString(line.reference()));
        }
      } catch (IllegalArgumentException ignored) {
        // a statement reference that is not a txn id (an injected extra line)
      }
    }
    for (TxnProjection txn : store.txnsById(ids, zone)) {
      byId.putIfAbsent(txn.txnId(), txn);
    }
    return List.copyOf(byId.values());
  }

  private Set<String> postedIds(List<TxnProjection> txns) {
    List<UUID> ids = new ArrayList<>();
    for (TxnProjection txn : txns) {
      try {
        ids.add(UUID.fromString(txn.txnId()));
      } catch (IllegalArgumentException ignored) {
        // projections only ever carry txn ids
      }
    }
    Set<String> posted = new java.util.HashSet<>();
    for (UUID id : store.posted(ids)) {
      posted.add(id.toString());
    }
    return posted;
  }

  private ExternalLine partnerLine(PartnerReconEvent event) {
    Map<String, String> fields = event.fields() == null ? Map.of() : event.fields();
    ReconProperties.Fields names = props.fields();
    return new ExternalLine(fields.get(names.partnerRef()), fields.get(names.reference()),
        minor(fields.get(names.amount())), fields.get(names.status()), date(fields.get(names.valueDate())));
  }

  private LocalDate date(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String text = raw.trim();
    if (text.length() == 10) {
      return LocalDate.parse(text);
    }
    return Instant.parse(text).atZone(zone()).toLocalDate();
  }

  private static long minor(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "settlement line has no amount");
    }
    String text = raw.trim().replace(" ", "");
    if (text.indexOf(',') >= 0 && text.indexOf('.') >= 0) {
      text = text.replace(",", "");
    } else if (text.indexOf(',') >= 0) {
      text = text.replace(',', '.');
    }
    return new BigDecimal(text).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  private static String currency(PartnerReconEvent event) {
    if (event.fields() == null) {
      return "BDT";
    }
    String currency = event.fields().get("currency");
    return currency == null || currency.isBlank() ? "BDT" : currency;
  }

  private static long sizeOf(String fileId) {
    int dash = fileId.lastIndexOf('-');
    int prev = dash < 0 ? -1 : fileId.lastIndexOf('-', dash - 1);
    if (prev < 0) {
      return 0;
    }
    try {
      return Long.parseLong(fileId.substring(prev + 1, dash));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private LocalDate businessDate(List<ExternalLine> lines, ZoneId zone) {
    LocalDate date = null;
    for (ExternalLine line : lines) {
      if (line.valueDate() != null && (date == null || line.valueDate().isAfter(date))) {
        date = line.valueDate();
      }
    }
    return date == null ? LocalDate.now(zone) : date;
  }

  private MatchRule rule() {
    return new MatchRule(props.match().toleranceMinor(), props.match().dateWindowDays());
  }

  private ZoneId zone() {
    return ZoneId.of(props.trust().zone());
  }

  private EventMetadata meta(String txnId, String idempotencyKey) {
    String correlation = MDC.get("correlationId");
    if (correlation == null || correlation.isBlank()) {
      correlation = "recon-" + txnId;
    }
    return new EventMetadata(correlation, txnId, idempotencyKey, 1);
  }

  private static Map<String, Object> resultBody(RunSummary run, List<Map<String, Object>> exceptions, Instant finished) {
    Map<String, Object> counts = new LinkedHashMap<>();
    counts.put("MATCHED", run.matched());
    counts.put("MISSING_IN_PARTNER", run.missingInPartner());
    counts.put("MISSING_IN_UPAY", run.missingInUpay());
    counts.put("AMOUNT_MISMATCH", run.amountMismatch());
    counts.put("STATUS_MISMATCH", run.statusMismatch());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", 1);
    body.put("runId", run.runId());
    body.put("partnerId", run.partnerId());
    body.put("source", run.source());
    body.put("fileName", run.fileName());
    body.put("businessDate", run.businessDate());
    body.put("counts", counts);
    body.put("exceptions", exceptions);
    body.put("finishedAt", finished.toString());
    return body;
  }

  private static Map<String, Object> exceptionBody(UUID id, Finding finding) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("exceptionId", id.toString());
    body.put("class", finding.classification().name());
    body.put("owner", finding.owner());
    body.put("state", "OPEN");
    put(body, "detail", finding.detail());
    put(body, "txnId", uuidText(finding.txnId()));
    put(body, "partnerRef", finding.partnerRef());
    put(body, "reference", ExceptionEvents.bounded(mfs.mw.recon.util.MaskingPolicy.scrub(finding.reference())));
    put(body, "fileAmountMinor", finding.fileAmountMinor());
    put(body, "upayAmountMinor", finding.upayAmountMinor());
    put(body, "ledgerAmountMinor", finding.ledgerAmountMinor());
    return body;
  }

  private static Map<String, Object> audit(FileHead file, String operatorId, RunSummary run, Instant at) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", 1);
    body.put("auditId", UUID.randomUUID().toString());
    body.put("correlationId", "recon-" + run.runId());
    body.put("actor", Map.of("type", "user", "id", operatorId));
    body.put("action", "recon.file.reprocessed");
    body.put("resource", "source_file/" + file.id());
    body.put("outcome", "SUCCESS");
    body.put("at", at.toString());
    body.put("service", "mw-recon");
    return body;
  }

  private static void put(Map<String, Object> body, String key, Object value) {
    if (value != null) {
      body.put(key, value);
    }
  }

  private static String uuidText(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value).toString();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new MwException(ErrorCodes.VAL_INVALID, name + " is required");
    }
  }
}
