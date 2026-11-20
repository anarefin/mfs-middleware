package mfs.mw.app.gateway.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Instant;
import mfs.mw.app.gateway.dto.SubmitResult;
import mfs.mw.app.gateway.dto.TransactionHistory;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.security.DeviceGate;
import mfs.mw.app.gateway.service.PreviewService;
import mfs.mw.app.gateway.service.TransactionHistoryService;
import mfs.mw.app.gateway.service.TransactionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

/** App transactions API v1 (spec 9.1; contract {@code api/mw-app-gateway/app.v1.openapi.yaml}). */
@RestController
@RequestMapping("/app/v1/transactions")
public class TransactionController {

  static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private final TransactionService service;
  private final TransactionHistoryService history;
  private final AppUserResolver users;
  private final PreviewService previews;

  public TransactionController(
      TransactionService service, TransactionHistoryService history, AppUserResolver users, PreviewService previews) {
    this.service = service;
    this.history = history;
    this.users = users;
    this.previews = previews;
  }

  /**
   * 200 with the terminal result, or 202 {@code PROCESSING} when the budget runs out (7.5). Moves
   * money: signed by the bound device (ticket 29). Stays for one release (PLT-051, ticket 105) and
   * runs the preview, then the confirm, of this body.
   */
  @PostMapping
  @DeviceGate(DeviceGate.Level.SIGNED)
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Timestamp", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Nonce", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Signature", required = true)
  public ResponseEntity<TransactionView> submit(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(IDEMPOTENCY_KEY)
          @Pattern(regexp = "[\\x21-\\x7E]{1,64}", message = "must be 1 to 64 visible ASCII characters")
          String idempotencyKey,
      @Valid @RequestBody TransactionRequest body) {
    SubmitResult result = previews.submitOneShot(users.resolve(jwt), idempotencyKey, body);
    if (result.done()) {
      return ResponseEntity.ok(result.view());
    }
    return ResponseEntity.accepted()
        .location(URI.create("/app/v1/transactions/" + result.view().txnId()))
        .body(result.view());
  }

  /**
   * The token's own wallet's history, sent and received, newest first (ADR D25). Session level (no
   * signature: nothing moves); any app role; the wallet is never a parameter.
   */
  @GetMapping
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public TransactionHistory history(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "" + TransactionHistoryService.DEFAULT_LIMIT)
          @Min(1) @Max(TransactionHistoryService.MAX_LIMIT) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(TransactionHistoryService.MAX_OFFSET) int offset,
      @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_]{1,32}") String txnType,
      @RequestParam(required = false) @Pattern(regexp = "IN|OUT") String direction,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to) {
    return history.history(users.resolve(jwt), limit, offset, txnType, direction, from, to);
  }

  @GetMapping("/{txnId}")
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public TransactionView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String txnId) {
    return service.find(users.resolve(jwt), txnId);
  }
}
