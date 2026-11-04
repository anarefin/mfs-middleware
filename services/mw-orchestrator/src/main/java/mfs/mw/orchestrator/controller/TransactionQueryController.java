package mfs.mw.orchestrator.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import mfs.mw.orchestrator.dto.TxnDetail;
import mfs.mw.orchestrator.dto.TxnPage;
import mfs.mw.orchestrator.dto.TxnSearchCriteria;
import mfs.mw.orchestrator.service.TransactionQueryService;
import mfs.mw.orchestrator.service.saga.Status;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal query API (ticket 35, contract {@code api/mw-orchestrator/txn-query.v1.openapi.yaml}):
 * reached over the {@code app} network by mw-admin-api and mw-portal-api only; no APISIX route
 * exposes it.
 */
@RestController
@RequestMapping("/internal/v1/transactions")
public class TransactionQueryController {

  private final TransactionQueryService queries;

  public TransactionQueryController(TransactionQueryService queries) {
    this.queries = queries;
  }

  @GetMapping
  public TxnPage search(
      @RequestParam(required = false) UUID txnId,
      @RequestParam(required = false) @Pattern(regexp = "\\+?[0-9 -]{8,20}") String msisdn,
      @RequestParam(required = false) @Size(min = 1, max = 64) String orgId,
      @RequestParam(required = false) @Size(min = 1, max = 128) String partnerRef,
      @RequestParam(required = false) Status status,
      @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_]{1,32}") String txnType,
      @RequestParam(required = false) @Size(min = 1, max = 64) String parentTxnId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int offset) {
    return queries.search(
        new TxnSearchCriteria(
            txnId, msisdn, orgId, partnerRef, status == null ? null : status.name(), txnType, parentTxnId,
            from, to, limit, offset));
  }

  @GetMapping("/{txnId}")
  public TxnDetail detail(
      @PathVariable UUID txnId, @RequestParam(required = false) @Size(min = 1, max = 64) String orgId) {
    return queries.detail(txnId, orgId);
  }
}
