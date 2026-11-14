package mfs.mw.crypto.controller;

import jakarta.validation.Valid;
import mfs.mw.crypto.dto.DerivePinRequest;
import mfs.mw.crypto.dto.DerivePinResponse;
import mfs.mw.crypto.dto.VerifyPinRequest;
import mfs.mw.crypto.dto.VerifyPinResponse;
import mfs.mw.crypto.service.PinService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Wallet PIN verify and derive (crypto-internal.v1, spec 18.7, 18.8; caller mw-auth). */
@RestController
@RequestMapping("/internal/v1/pin")
public class PinController {

  private final PinService pins;

  public PinController(PinService pins) {
    this.pins = pins;
  }

  @PostMapping("/verify")
  public VerifyPinResponse verify(@Valid @RequestBody VerifyPinRequest request) {
    return pins.verify(request);
  }

  @PostMapping("/derive")
  public DerivePinResponse derive(@Valid @RequestBody DerivePinRequest request) {
    return pins.derive(request);
  }
}
