package mfs.mw.app.gateway.controller;

import mfs.mw.app.gateway.dto.PinKey;
import mfs.mw.app.gateway.security.DeviceGate;
import mfs.mw.app.gateway.service.PinKeyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /app/v1/pin-key} (ticket 72; contract {@code api/mw-app-gateway/app.v1.openapi.yaml}):
 * the public key the app encrypts the PIN with for {@code POST /app/v1/transactions}. A public key,
 * so behind the token only (no device gate).
 */
@RestController
public class PinKeyController {

  private final PinKeyService keys;

  public PinKeyController(PinKeyService keys) {
    this.keys = keys;
  }

  @GetMapping("/app/v1/pin-key")
  @DeviceGate(DeviceGate.Level.NONE)
  public PinKey pinKey() {
    return keys.current();
  }
}
