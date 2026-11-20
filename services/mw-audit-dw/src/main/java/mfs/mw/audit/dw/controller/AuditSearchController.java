package mfs.mw.audit.dw.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import mfs.mw.audit.dw.dto.AuditEntry;
import mfs.mw.audit.dw.dto.AuditSearchCriteria;
import mfs.mw.audit.dw.dto.TimelineHop;
import mfs.mw.audit.dw.service.AuditExportService;
import mfs.mw.audit.dw.service.AuditSearchService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal audit API (tickets 35, 130; contract {@code api/mw-audit-dw/audit-query.v1.openapi.yaml}):
 * the search, one transaction's per-hop timeline and the audited CSV export, reached over the
 * {@code app} network by mw-admin-api only; no APISIX route exposes it.
 */
@RestController
@RequestMapping("/internal/v1/audit")
public class AuditSearchController {

  public static final String OPERATOR_HEADER = "X-Mw-Operator-Id";
  public static final String TRUNCATED_HEADER = "X-Mw-Export-Truncated";

  private final AuditSearchService search;
  private final AuditExportService export;

  public AuditSearchController(AuditSearchService search, AuditExportService export) {
    this.search = search;
    this.export = export;
  }

  @GetMapping("/events")
  public AuditEntry.Page search(
      @RequestParam(required = false) UUID txnId,
      @RequestParam(required = false) @Size(min = 1, max = 32) String actorType,
      @RequestParam(required = false) @Size(min = 1, max = 128) String actorId,
      @RequestParam(required = false) @Size(min = 1, max = 128) String action,
      @RequestParam(required = false) @Size(min = 1, max = 256) String resource,
      @RequestParam(required = false) @Size(min = 1, max = 32) String outcome,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int offset,
      @RequestParam(required = false) @Size(min = 1, max = 64) String partner,
      @RequestParam(required = false) @Size(min = 1, max = 32) String channel,
      @RequestParam(required = false) @Size(min = 1, max = 32) String status,
      @RequestParam(required = false) @Min(0) Long amountMin,
      @RequestParam(required = false) @Min(0) Long amountMax,
      @RequestParam(required = false) @Size(min = 11, max = 20) String msisdn) {
    return search.search(
        new AuditSearchCriteria(txnId, actorType, actorId, action, resource, outcome, from, to, limit, offset,
            partner, channel, status, amountMin, amountMax, null),
        msisdn);
  }

  @GetMapping("/transactions/{txnId}/timeline")
  public TimelineHop.Timeline timeline(@PathVariable UUID txnId) {
    return search.timeline(txnId);
  }

  @GetMapping(value = "/search/export.csv", produces = "text/csv")
  public ResponseEntity<byte[]> export(
      @RequestHeader(name = OPERATOR_HEADER, required = false) @Size(min = 1, max = 128) String operatorId,
      @RequestParam(required = false) UUID txnId,
      @RequestParam(required = false) @Size(min = 1, max = 32) String actorType,
      @RequestParam(required = false) @Size(min = 1, max = 128) String actorId,
      @RequestParam(required = false) @Size(min = 1, max = 128) String action,
      @RequestParam(required = false) @Size(min = 1, max = 256) String resource,
      @RequestParam(required = false) @Size(min = 1, max = 32) String outcome,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) @Size(min = 1, max = 64) String partner,
      @RequestParam(required = false) @Size(min = 1, max = 32) String channel,
      @RequestParam(required = false) @Size(min = 1, max = 32) String status,
      @RequestParam(required = false) @Min(0) Long amountMin,
      @RequestParam(required = false) @Min(0) Long amountMax,
      @RequestParam(required = false) @Size(min = 11, max = 20) String msisdn) {
    AuditExportService.Export out =
        export.export(
            new AuditSearchCriteria(txnId, actorType, actorId, action, resource, outcome, from, to, 1, 0, partner,
                channel, status, amountMin, amountMax, null),
            msisdn,
            operatorId);
    return csv(out);
  }

  static ResponseEntity<byte[]> csv(AuditExportService.Export out) {
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-export.csv\"")
        .header(TRUNCATED_HEADER, String.valueOf(out.truncated()))
        .body(out.csv().getBytes(StandardCharsets.UTF_8));
  }
}
