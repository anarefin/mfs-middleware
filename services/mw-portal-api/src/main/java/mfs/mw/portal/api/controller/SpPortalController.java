package mfs.mw.portal.api.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import mfs.mw.portal.api.controller.OrgTransactionController.TxnStatus;
import mfs.mw.portal.api.dto.OrgTxn;
import mfs.mw.portal.api.security.PortalUserResolver;
import mfs.mw.portal.api.service.SpPortalService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SP portal v1 (ticket 129d; contract {@code api/mw-portal-api/portal-sp.v1.openapi.yaml}), behind
 * the edge route {@code /portal/sp/v1/*}. Read-only; the org is the token's, there is no org
 * parameter (an unknown query parameter is ignored).
 */
@RestController
@RequestMapping("/portal/sp/v1")
public class SpPortalController {

  static final MediaType TEXT_CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");
  private static final DateTimeFormatter FILE_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  private final SpPortalService service;
  private final PortalUserResolver users;

  public SpPortalController(SpPortalService service, PortalUserResolver users) {
    this.service = service;
    this.users = users;
  }

  @GetMapping("/transactions")
  public OrgTxn.Page search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) TxnStatus status,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int offset) {
    return service.search(users.resolve(jwt), status == null ? null : status.name(), from, to, limit, offset);
  }

  @GetMapping("/transactions/{txnId}")
  public OrgTxn.Detail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID txnId) {
    return service.detail(users.resolve(jwt), txnId);
  }

  @GetMapping("/reports")
  public ResponseEntity<String> report(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(required = false) TxnStatus status) {
    String csv = service.report(users.resolve(jwt), status == null ? null : status.name(), from, to);
    return ResponseEntity.ok()
        .contentType(TEXT_CSV)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"sp-report-" + FILE_TIME.format(from) + "-" + FILE_TIME.format(to) + ".csv\"")
        .body(csv);
  }
}
