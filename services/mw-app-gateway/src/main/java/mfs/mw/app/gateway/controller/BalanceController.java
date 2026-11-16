package mfs.mw.app.gateway.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import mfs.mw.app.gateway.dto.BalanceRequest;
import mfs.mw.app.gateway.dto.BalanceView;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.security.DeviceGate;
import mfs.mw.app.gateway.service.BalanceService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /app/v1/balance} (ADR D26; contract {@code api/mw-app-gateway/app.v1.openapi.yaml}):
 * the token's own wallet's available balance. Needs the PIN, so signed by the bound device like a
 * submit; any app role.
 */
@RestController
public class BalanceController {

  private final BalanceService service;
  private final AppUserResolver users;

  public BalanceController(BalanceService service, AppUserResolver users) {
    this.service = service;
    this.users = users;
  }

  @PostMapping("/app/v1/balance")
  @DeviceGate(DeviceGate.Level.SIGNED)
  public BalanceView check(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(TransactionController.IDEMPOTENCY_KEY)
          @Pattern(regexp = "[\\x21-\\x7E]{1,64}", message = "must be 1 to 64 visible ASCII characters")
          String idempotencyKey,
      @Valid @RequestBody BalanceRequest body) {
    return service.check(users.resolve(jwt), idempotencyKey, body);
  }
}
