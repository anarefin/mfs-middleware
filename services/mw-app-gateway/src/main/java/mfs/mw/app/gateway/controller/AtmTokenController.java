package mfs.mw.app.gateway.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import mfs.mw.app.gateway.dto.AtmTokenRequest;
import mfs.mw.app.gateway.dto.AtmTokenView;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.security.DeviceGate;
import mfs.mw.app.gateway.service.AtmTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /app/v1/atm/tokens} (ticket 120b2, FRS FL-11; contract {@code
 * api/mw-app-gateway/app.v1.openapi.yaml}): the ATM cash-out token for an amount, after the PIN
 * step-up (signed, PIN). 201 with the token, its expiry and the amount it is bound to.
 */
@RestController
public class AtmTokenController {

  private final AtmTokenService service;
  private final AppUserResolver users;

  public AtmTokenController(AtmTokenService service, AppUserResolver users) {
    this.service = service;
    this.users = users;
  }

  @PostMapping("/app/v1/atm/tokens")
  @ResponseStatus(HttpStatus.CREATED)
  @DeviceGate(DeviceGate.Level.SIGNED)
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Timestamp", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Nonce", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Signature", required = true)
  public AtmTokenView issue(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(TransactionController.IDEMPOTENCY_KEY)
          @Pattern(regexp = "[\\x21-\\x7E]{1,64}", message = "must be 1 to 64 visible ASCII characters")
          String idempotencyKey,
      @Valid @RequestBody AtmTokenRequest body) {
    return service.issue(users.resolve(jwt), idempotencyKey, body);
  }
}
