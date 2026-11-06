package mfs.mw.audit.dw.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import mfs.mw.audit.dw.dto.AuditEntry;
import mfs.mw.audit.dw.dto.AuditSearchCriteria;
import mfs.mw.audit.dw.service.AuditSearchService;
import mfs.mw.audit.dw.service.AuditTestService;
import mfs.mw.audit.dw.service.ChainVerification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only audit API (ticket 58, contract {@code api/mw-audit-dw/audit-dw-test.v1.openapi.yaml}),
 * for the Newman suite in {@code tests/api} through the test-only APISIX route
 * {@code /test/audit-dw/v1/*}: the same search as {@link AuditSearchController} without the
 * services-realm token, and the hash-chain check of one month. Only with
 * {@code mw.audit-dw.test-api.enabled=true} ({@code MW_AUDIT_DW_TEST_API_ENABLED}, set by the dev
 * stack); without it neither path exists.
 */
@RestController
@RequestMapping("/test/v1/audit")
@ConditionalOnProperty(name = "mw.audit-dw.test-api.enabled", havingValue = "true")
public class AuditTestController {

  private final AuditSearchService search;
  private final AuditTestService tests;

  public AuditTestController(AuditSearchService search, AuditTestService tests) {
    this.search = search;
    this.tests = tests;
  }

  @GetMapping("/events")
  public AuditEntry.Page events(
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

  /** The month's chain recomputed as the read-only reader ({@code service/AuditTestService}). */
  @GetMapping("/chains/{month}")
  public ChainVerification chain(@PathVariable @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String month) {
    return tests.verifyChain(month);
  }
}
