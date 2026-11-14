package mfs.mw.auth.controller;

import jakarta.validation.Valid;
import mfs.mw.auth.dto.DeviceOtpSent;
import mfs.mw.auth.dto.InternalOtpRequest;
import mfs.mw.auth.dto.InternalOtpVerification;
import mfs.mw.auth.service.ChannelOtpService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The checkout OTP (ticket 72; contract {@code auth-internal.v1.openapi.yaml}) on the internal mTLS
 * port; the caller is the services token's {@code azp} (mw-checkout only).
 */
@RestController
public class InternalOtpController {

  private final ChannelOtpService otps;

  public InternalOtpController(ChannelOtpService otps) {
    this.otps = otps;
  }

  @PostMapping("/internal/v1/otp")
  public ResponseEntity<DeviceOtpSent> send(@AuthenticationPrincipal Jwt caller, @Valid @RequestBody InternalOtpRequest body) {
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(otps.send(azp(caller), body));
  }

  @PostMapping("/internal/v1/otp/verifications")
  public ResponseEntity<Void> verify(
      @AuthenticationPrincipal Jwt caller, @Valid @RequestBody InternalOtpVerification body) {
    otps.verify(azp(caller), body);
    return ResponseEntity.noContent().build();
  }

  private static String azp(Jwt caller) {
    return caller == null ? null : caller.getClaimAsString("azp");
  }
}
