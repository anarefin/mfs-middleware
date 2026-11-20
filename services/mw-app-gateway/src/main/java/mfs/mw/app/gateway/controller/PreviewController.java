package mfs.mw.app.gateway.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import mfs.mw.app.gateway.dto.ConfirmRequest;
import mfs.mw.app.gateway.dto.PreviewRequest;
import mfs.mw.app.gateway.dto.PreviewView;
import mfs.mw.app.gateway.dto.SubmitResult;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.security.DeviceGate;
import mfs.mw.app.gateway.service.PreviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two-step financial API (ticket 105, FRS CH-APP-05, PLT-026; contract {@code
 * api/mw-app-gateway/app.v1.openapi.yaml} 1.9.0): {@code POST /app/v1/{group}/preview} (session level,
 * no PIN, nothing moves) and {@code POST /app/v1/{group}/confirm} (signed, Idempotency-Key, PIN), the
 * group one of {@link PreviewService#GROUPS}.
 */
@RestController
@RequestMapping("/app/v1")
public class PreviewController {

  static final String GROUP =
      "{group:send-money|cash-in|cash-out|payment|bill-pay|top-up|add-money}";

  private final PreviewService previews;
  private final AppUserResolver users;

  public PreviewController(PreviewService previews, AppUserResolver users) {
    this.previews = previews;
    this.users = users;
  }

  @PostMapping("/" + GROUP + "/preview")
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public PreviewView preview(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String group, @Valid @RequestBody PreviewRequest body) {
    return previews.preview(users.resolve(jwt), group, body);
  }

  /** As {@code POST /app/v1/transactions}: 200 with the terminal result, or 202 {@code PROCESSING}. */
  @PostMapping("/" + GROUP + "/confirm")
  @DeviceGate(DeviceGate.Level.SIGNED)
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Timestamp", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Nonce", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Signature", required = true)
  public ResponseEntity<TransactionView> confirm(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String group,
      @RequestHeader(TransactionController.IDEMPOTENCY_KEY)
          @Pattern(regexp = "[\\x21-\\x7E]{1,64}", message = "must be 1 to 64 visible ASCII characters")
          String idempotencyKey,
      @Valid @RequestBody ConfirmRequest body) {
    SubmitResult result = previews.confirm(users.resolve(jwt), group, idempotencyKey, body);
    if (result.done()) {
      return ResponseEntity.ok(result.view());
    }
    return ResponseEntity.accepted()
        .location(URI.create("/app/v1/transactions/" + result.view().txnId()))
        .body(result.view());
  }
}
