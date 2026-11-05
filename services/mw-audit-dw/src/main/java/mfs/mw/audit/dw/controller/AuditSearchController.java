package mfs.mw.audit.dw.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import mfs.mw.audit.dw.dto.AuditEntry;
import mfs.mw.audit.dw.dto.AuditSearchCriteria;
import mfs.mw.audit.dw.service.AuditSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal audit search (ticket 35, contract {@code api/mw-audit-dw/audit-query.v1.openapi.yaml}):
 * reached over the {@code app} network by mw-admin-api only; no APISIX route exposes it.
 */
@RestController
@RequestMapping("/internal/v1/audit/events")
public class AuditSearchController {

  private final AuditSearchService search;

  public AuditSearchController(AuditSearchService search) {
    this.search = search;
  }

  @GetMapping
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
      @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int offset) {
    return search.search(
        new AuditSearchCriteria(txnId, actorType, actorId, action, resource, outcome, from, to, limit, offset));
  }
}
