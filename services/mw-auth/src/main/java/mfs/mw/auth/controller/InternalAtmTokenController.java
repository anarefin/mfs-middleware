package mfs.mw.auth.controller;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import mfs.mw.auth.dto.AtmTokenIssued;
import mfs.mw.auth.dto.AtmTokenRequest;
import mfs.mw.auth.dto.AtmTokenVerification;
import mfs.mw.auth.dto.AtmTokenVerified;
import mfs.mw.auth.service.AtmTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ATM cash-out token (ticket 120b2; contract {@code auth-internal.v1.openapi.yaml}) on the
 * internal mTLS port; the caller is the services token's {@code azp}.
 */
@RestController
public class InternalAtmTokenController {

  private final AtmTokenService tokens;

  public InternalAtmTokenController(AtmTokenService tokens) {
    this.tokens = tokens;
  }

  @PostMapping("/internal/v1/atm-tokens")
  @ApiResponse(responseCode = "201", description = "Token issued")
  public ResponseEntity<AtmTokenIssued> issue(@AuthenticationPrincipal Jwt caller, @Valid @RequestBody AtmTokenRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED).body(tokens.issue(azp(caller), body));
  }

  @PostMapping("/internal/v1/atm-tokens/verifications")
  public AtmTokenVerified verify(@AuthenticationPrincipal Jwt caller, @Valid @RequestBody AtmTokenVerification body) {
    return tokens.verify(azp(caller), body);
  }

  private static String azp(Jwt caller) {
    return caller == null ? null : caller.getClaimAsString("azp");
  }
}
