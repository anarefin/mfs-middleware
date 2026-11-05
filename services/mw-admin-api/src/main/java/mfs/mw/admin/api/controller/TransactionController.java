package mfs.mw.admin.api.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import mfs.mw.admin.api.dto.AdminTxn;
import mfs.mw.admin.api.dto.TxnDetailView;
import mfs.mw.admin.api.security.OperatorResolver;
import mfs.mw.admin.api.service.TransactionQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transaction search and detail (ticket 36; contract {@code api/mw-admin-api/admin.v1.openapi.yaml}).
 * Roles are checked by {@code config/SecurityConfig} and again by the service.
 */
@RestController
@RequestMapping("/admin/v1/transactions")
public class TransactionController {

  /** The saga states, checked here so a typo never reaches the orchestrator. */
  enum TxnStatus {
    RECEIVED, VALIDATING, HOLDING, POSTING, PARTNER_PENDING, UNKNOWN, COMMITTING, RELEASING, REVERSING,
    COMPLETED, FAILED, REJECTED, REVERSED, MANUAL_REVIEW
  }

  private final TransactionQueryService service;
  private final OperatorResolver operators;

  public TransactionController(TransactionQueryService service, OperatorResolver operators) {
    this.service = service;
    this.operators = operators;
  }

  @GetMapping
  public AdminTxn.Page search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) UUID txnId,
      @RequestParam(required = false) @Pattern(regexp = "\\+?[0-9 -]{8,20}") String msisdn,
      @RequestParam(required = false) @Size(min = 1, max = 128) String partnerRef,
      @RequestParam(required = false) TxnStatus status,
      @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_]{1,32}") String txnType,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int offset) {
    return service.search(
        operators.resolve(jwt),
        new TransactionQueryService.Criteria(
            txnId, msisdn, partnerRef, status == null ? null : status.name(), txnType, from, to, limit, offset));
  }

  @GetMapping("/{txnId}")
  public TxnDetailView detail(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID txnId, @RequestParam(defaultValue = "false") boolean reveal) {
    return service.detail(operators.resolve(jwt), txnId, reveal);
  }
}
