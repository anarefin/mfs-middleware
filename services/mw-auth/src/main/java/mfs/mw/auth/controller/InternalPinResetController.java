package mfs.mw.auth.controller;

import jakarta.validation.Valid;
import mfs.mw.auth.dto.CarePinReset;
import mfs.mw.auth.dto.CarePinResetRequest;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.service.PinResetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The care PIN reset of mw-admin-api (ticket 70; contract {@code auth-internal.v1.openapi.yaml}), on
 * the internal mTLS port. Only the client {@value #CALLER}: the channels on the allow-list may not open
 * a reset (403 MW-AUTH-002).
 */
@RestController
public class InternalPinResetController {

  /** The one client that may open a care reset (after maker-checker). */
  public static final String CALLER = "mw-admin-api";
  private static final Logger log = LoggerFactory.getLogger(InternalPinResetController.class);

  private final PinResetService resets;

  public InternalPinResetController(PinResetService resets) {
    this.resets = resets;
  }

  @PostMapping("/internal/v1/pin-resets")
  public ResponseEntity<CarePinReset> approve(
      @AuthenticationPrincipal Jwt caller, @Valid @RequestBody CarePinResetRequest body) {
    if (caller == null || !CALLER.equals(caller.getClaimAsString("azp"))) {
      log.atWarn().addKeyValue("event", "security.internal_call_denied").addKeyValue("reason", "CLIENT_NOT_ALLOWED")
          .log("Care PIN reset refused for this client");
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "Not allowed").withStatus(HttpStatus.FORBIDDEN);
    }
    PinResetService.Care care = resets.approveCare(body);
    return ResponseEntity.status(care.created() ? HttpStatus.CREATED : HttpStatus.OK).body(care.reset());
  }
}
