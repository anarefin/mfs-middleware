package mfs.mw.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import mfs.mw.auth.dto.ChangePinRequest;
import mfs.mw.auth.dto.LoginRequest;
import mfs.mw.auth.dto.RefreshRequest;
import mfs.mw.auth.dto.SetPinRequest;
import mfs.mw.auth.dto.TokenPair;
import mfs.mw.auth.security.SignedRequests;
import mfs.mw.auth.service.LoginService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login, refresh, logout, the first PIN and the PIN change (ticket 70) (spec 18.7, D21; contract {@code auth.v1.openapi.yaml},
 * ticket 69). Login and refresh are signed by the device; logout and the first PIN take mw-auth's own
 * access token.
 */
@RestController
public class LoginController {

  private final LoginService logins;

  public LoginController(LoginService logins) {
    this.logins = logins;
  }

  @PostMapping("/auth/v1/sessions")
  public TokenPair login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
    return logins.login(body.msisdn(), body.encryptedPin(), body.otp(), SignedRequests.from(request));
  }

  @PostMapping("/auth/v1/tokens/refresh")
  public TokenPair refresh(@Valid @RequestBody RefreshRequest body, HttpServletRequest request) {
    return logins.refresh(body.refreshToken(), SignedRequests.from(request));
  }

  @PostMapping("/auth/v1/sessions/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@AuthenticationPrincipal Jwt jwt) {
    logins.logout(jwt.getSubject(), jwt.getClaimAsString("deviceId"), jwt.getClaimAsString("sid"));
  }

  @PostMapping("/auth/v1/pin")
  public TokenPair setPin(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SetPinRequest body) {
    return logins.setFirstPin(jwt.getSubject(), jwt.getClaimAsString("msisdn"), jwt.getClaimAsString("deviceId"),
        jwt.getClaimAsString("sid"), body.encryptedPin());
  }

  /** Ticket 70: the PIN change, step-up with the current PIN in the body. */
  @PutMapping("/auth/v1/pin")
  public TokenPair changePin(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePinRequest body) {
    return logins.changePin(jwt.getSubject(), jwt.getClaimAsString("msisdn"), jwt.getClaimAsString("deviceId"),
        jwt.getClaimAsString("sid"), jwt.getClaimAsStringList("amr"), body.encryptedPin(), body.newEncryptedPin());
  }
}
