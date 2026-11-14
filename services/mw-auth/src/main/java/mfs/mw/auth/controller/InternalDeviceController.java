package mfs.mw.auth.controller;

import mfs.mw.auth.dto.BoundDevice;
import mfs.mw.auth.dto.LiveSession;
import mfs.mw.auth.service.DeviceBindingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The device lookups mw-app-gateway's verifier asks for (ticket 68; contract {@code
 * auth-internal.v1.openapi.yaml}), on the internal mTLS port only ({@code config/SecurityConfig}).
 */
@RestController
public class InternalDeviceController {

  private final DeviceBindingService devices;

  public InternalDeviceController(DeviceBindingService devices) {
    this.devices = devices;
  }

  /** The bound device's wallet and public key; 404 MW-AUTH-003 when it is not bound. */
  @GetMapping("/internal/v1/devices/{deviceId}")
  public BoundDevice device(@PathVariable String deviceId) {
    return devices.device(deviceId);
  }

  /** The wallet's live session on the device, extended by its idle timeout; 404 MW-AUTH-007 when none. */
  @GetMapping("/internal/v1/sessions/{walletId}/{deviceId}")
  public LiveSession session(@PathVariable String walletId, @PathVariable String deviceId) {
    return devices.checkSession(walletId, deviceId);
  }
}
