package mfs.mw.app.gateway.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import mfs.mw.app.gateway.dto.SubmitResult;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.security.DeviceGate;
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
import org.springframework.web.bind.annotation.RestController;

/** App transactions API v1 (spec 9.1; contract {@code api/mw-app-gateway/app.v1.openapi.yaml}). */
@RestController
@RequestMapping("/app/v1/transactions")
public class TransactionController {

  static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private final TransactionService service;
  private final AppUserResolver users;

  public TransactionController(TransactionService service, AppUserResolver users) {
    this.service = service;
    this.users = users;
  }

  /**
   * 200 with the terminal result, or 202 {@code PROCESSING} when the budget runs out (7.5). Moves
   * money: signed by the bound device (ticket 29).
   */
  @PostMapping
  @DeviceGate(DeviceGate.Level.SIGNED)
  public ResponseEntity<TransactionView> submit(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(IDEMPOTENCY_KEY)
          @Pattern(regexp = "[\\x21-\\x7E]{1,64}", message = "must be 1 to 64 visible ASCII characters")
          String idempotencyKey,
      @Valid @RequestBody TransactionRequest body) {
    SubmitResult result = service.submit(users.resolve(jwt), idempotencyKey, body);
    if (result.done()) {
      return ResponseEntity.ok(result.view());
    }
    return ResponseEntity.accepted()
        .location(URI.create("/app/v1/transactions/" + result.view().txnId()))
        .body(result.view());
  }

  @GetMapping("/{txnId}")
  public TransactionView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String txnId) {
    return service.find(users.resolve(jwt), txnId);
  }
}
