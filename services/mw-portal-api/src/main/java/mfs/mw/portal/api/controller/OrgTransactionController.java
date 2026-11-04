package mfs.mw.portal.api.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import mfs.mw.portal.api.dto.OrgTxn;
import mfs.mw.portal.api.security.PortalUserResolver;
import mfs.mw.portal.api.service.OrgTransactionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SP transaction query v1 (ticket 35; contract {@code api/mw-portal-api/portal-org.v1.openapi.yaml}),
 * behind the existing edge route {@code /portal/org/v1/*}. The org is the token's; there is no org
 * parameter.
 */
@RestController
@RequestMapping("/portal/org/v1/transactions")
public class OrgTransactionController {

  /** The saga states of mw.txn.status.v1, checked here so a typo never reaches the orchestrator. */
  enum TxnStatus {
    RECEIVED, VALIDATING, HOLDING, POSTING, PARTNER_PENDING, UNKNOWN, COMMITTING, RELEASING, REVERSING,
    COMPLETED, FAILED, REJECTED, REVERSED, MANUAL_REVIEW
  }

  private final OrgTransactionService service;
  private final PortalUserResolver users;

  public OrgTransactionController(OrgTransactionService service, PortalUserResolver users) {
    this.service = service;
    this.users = users;
  }

  @GetMapping
  public OrgTxn.Page search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String batchId,
      @RequestParam(required = false) TxnStatus status,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int offset) {
    return service.search(
        users.resolve(jwt), batchId, status == null ? null : status.name(), from, to, limit, offset);
  }

  @GetMapping("/{txnId}")
  public OrgTxn.Detail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID txnId) {
    return service.detail(users.resolve(jwt), txnId);
  }
}
