package mfs.mw.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import mfs.mw.auth.dto.PinResetRequest;
import mfs.mw.auth.dto.PinResetView;
import mfs.mw.auth.dto.SetPinRequest;
import mfs.mw.auth.security.SignedRequests;
import mfs.mw.auth.service.PinResetService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The PIN reset of the app (spec 18.7, ticket 70; contract {@code auth.v1.openapi.yaml}). */
@RestController
public class PinResetController {

  private final PinResetService resets;

  public PinResetController(PinResetService resets) {
    this.resets = resets;
  }

  @PostMapping("/auth/v1/pin-resets")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public PinResetView start(@Valid @RequestBody PinResetRequest body, HttpServletRequest request) {
    return resets.start(body, SignedRequests.from(request));
  }

  @GetMapping("/auth/v1/pin-resets/{resetId}")
  public PinResetView get(
      @PathVariable UUID resetId, @RequestHeader(name = SignedRequests.DEVICE_ID, required = false) String deviceId) {
    return resets.get(resetId, deviceId);
  }

  @PutMapping("/auth/v1/pin-resets/{resetId}/pin")
  public PinResetView setPin(
      @PathVariable UUID resetId,
      @RequestHeader(name = SignedRequests.DEVICE_ID, required = false) String deviceId,
      @Valid @RequestBody SetPinRequest body) {
    return resets.setPin(resetId, deviceId, body.encryptedPin());
  }
}
