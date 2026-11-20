package mfs.mw.audit.dw.service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import mfs.mw.audit.dw.config.AuditQueryProperties;
import mfs.mw.audit.dw.dto.AuditEntry;
import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.dto.AuditSearchCriteria;
import mfs.mw.audit.dw.dto.ErrorDetail;
import mfs.mw.audit.dw.exception.ErrorCodes;
import mfs.mw.audit.dw.exception.MwException;
import mfs.mw.audit.dw.repository.AuditSearchRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * CSV export of an audit search (ticket 130, FRS AUD-05): the same filters as the search, at most
 * {@code mw.audit-dw.query.export-max-rows} rows in the order they happened, and every export is
 * itself audited: one operator-action record {@code audit.search.exported} in the hash chain (actor
 * the operator, the filters with an MSISDN shown only as given, the row count and whether the cap
 * cut it). The record is written before the CSV is returned, so no export goes unrecorded. Values
 * are as stored (already masked); a cell that a spreadsheet would run as a formula is prefixed with
 * {@code '}.
 */
@Service
public class AuditExportService {

  public static final String ACTION = "audit.search.exported";
  static final String SERVICE = "mw-audit-dw";

  static final List<String> HEADER =
      List.of(
          "at", "recordedAt", "auditId", "txnId", "correlationId", "hop", "direction", "durationMs", "actorType",
          "actorId", "action", "resource", "outcome", "service", "payloadRef", "month", "seq");

  private static final Pattern OPERATOR = Pattern.compile("[A-Za-z0-9._@-]{1,128}");
  private static final Pattern UUID_TEXT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

  /** One export: the CSV text, its row count and whether the row cap cut it. */
  public record Export(String csv, int rows, boolean truncated) {}

  private final AuditSearchService search;
  private final AuditSearchRepository repository;
  private final AuditService audit;
  private final AuditQueryProperties props;
  private final JsonMapper json;
  private final Clock clock;

  public AuditExportService(
      AuditSearchService search, AuditSearchRepository repository, AuditService audit, AuditQueryProperties props,
      JsonMapper json, Clock clock) {
    this.search = search;
    this.repository = repository;
    this.audit = audit;
    this.props = props;
    this.json = json;
    this.clock = clock;
  }

  /**
   * @param operatorId the operator who asked (mw-admin-api passes it; staff id, not masked)
   * @param msisdn a party's MSISDN filter as typed, or null
   */
  public Export export(AuditSearchCriteria criteria, String msisdn, String operatorId) {
    if (operatorId == null || !OPERATOR.matcher(operatorId).matches()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail("X-Mw-Operator-Id", "is required: the operator who exports")));
    }
    int cap = props.exportMaxRows();
    AuditSearchCriteria resolved = search.resolve(criteria, msisdn).withPage(cap + 1, 0);
    List<AuditEntry> rows = repository.search(resolved);
    boolean truncated = rows.size() > cap;
    List<AuditEntry> kept = truncated ? rows.subList(0, cap) : rows;

    audit.record(operatorAction(criteria, msisdn != null, operatorId, kept.size(), truncated));

    StringBuilder csv = new StringBuilder();
    line(csv, HEADER);
    for (AuditEntry e : kept) {
      line(
          csv,
          List.of(
              text(e.at()), text(e.recordedAt()), e.auditId(), text(e.txnId()), text(e.correlationId()),
              text(e.hop()), text(e.direction()), text(e.durationMs()), e.actor().type(), e.actor().id(),
              e.action(), e.resource(), e.outcome(), e.service(), text(e.payloadRef()), e.month(),
              String.valueOf(e.seq())));
    }
    return new Export(csv.toString(), kept.size(), truncated);
  }

  private AuditEvent operatorAction(
      AuditSearchCriteria c, boolean msisdnGiven, String operatorId, int rows, boolean truncated) {
    Map<String, Object> filters = new LinkedHashMap<>();
    put(filters, "txnId", c.txnId());
    put(filters, "actorType", c.actorType());
    put(filters, "actorId", c.actorId());
    put(filters, "action", c.action());
    put(filters, "resource", c.resource());
    put(filters, "outcome", c.outcome());
    put(filters, "from", c.from());
    put(filters, "to", c.to());
    put(filters, "partner", c.partner());
    put(filters, "channel", c.channel());
    put(filters, "status", c.status());
    put(filters, "amountMin", c.amountMin());
    put(filters, "amountMax", c.amountMax());
    if (msisdnGiven) {
      filters.put("msisdn", "given"); // the number itself is not recorded
    }
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("operation", "export.csv");
    after.put("filters", filters);
    after.put("rowCount", rows);
    after.put("truncated", truncated);
    String correlationId = MDC.get("correlationId");
    return new AuditEvent(
        1,
        UUID.randomUUID(),
        null,
        correlationId != null && UUID_TEXT.matcher(correlationId).matches()
            ? correlationId
            : UUID.randomUUID().toString(),
        new AuditEvent.Actor("user", operatorId),
        ACTION,
        "audit/search",
        null,
        json.valueToTree(after),
        null,
        "SUCCESS",
        clock.instant(),
        null,
        SERVICE);
  }

  private static void put(Map<String, Object> m, String k, Object v) {
    if (v != null) {
      m.put(k, v.toString());
    }
  }

  private static String text(Object v) {
    return v == null ? "" : v.toString();
  }

  private static void line(StringBuilder out, List<String> cells) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append(cell(cells.get(i)));
    }
    out.append("\r\n");
  }

  /** RFC 4180 quoting, and a {@code '} before a leading {@code = + - @} (CSV injection). */
  static String cell(String v) {
    String s = v == null ? "" : v;
    if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
      s = "'" + s;
    }
    return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")
        ? "\"" + s.replace("\"", "\"\"") + "\""
        : s;
  }
}
