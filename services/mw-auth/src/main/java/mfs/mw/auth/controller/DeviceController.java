package mfs.mw.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import mfs.mw.auth.dto.DeviceBindingRequest;
import mfs.mw.auth.dto.DeviceBound;
import mfs.mw.auth.dto.DeviceOtpSent;
import mfs.mw.auth.dto.OtpRequest;
import mfs.mw.auth.security.SignedRequests;
import mfs.mw.auth.service.DeviceBindingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

/**
 * The OTP and device binding (spec 9.1, 18.7; contract {@code auth.v1.openapi.yaml}; moved from
 * mw-app-gateway's {@code /app/v1/devices*} in ticket 68). No bearer token since ticket 69: the wallet
 * is named by its MSISDN. The OTP request is not signed (the key may not be bound yet); binding is
 * signed with the key being registered ({@code X-Device-Id}, {@code X-Timestamp}, {@code X-Nonce},
 * {@code X-Signature}).
 */
@RestController
public class DeviceController {

  private final DeviceBindingService devices;

  public DeviceController(DeviceBindingService devices) {
    this.devices = devices;
  }

  /** Sends an OTP for the device in {@code X-Device-Id} by SMS to the wallet of the MSISDN. */
  @PostMapping("/auth/v1/otp")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  public DeviceOtpSent sendOtp(
      @Valid @RequestBody OtpRequest body,
      @RequestHeader(name = SignedRequests.DEVICE_ID, required = false) String deviceId) {
    return devices.sendOtp(body.msisdn(), deviceId, body.purpose(), body.locale());
  }

  /** Binds the device to the wallet, signed with the key being registered, with its OTP (and PIN). */
  @PostMapping("/auth/v1/devices")
  @ResponseStatus(HttpStatus.CREATED)
  @Parameter(in = ParameterIn.HEADER, name = "X-Device-Id", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Timestamp", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Nonce", required = true)
  @Parameter(in = ParameterIn.HEADER, name = "X-Signature", required = true)
  public DeviceBound bind(@Valid @RequestBody DeviceBindingRequest body, HttpServletRequest request) {
    return devices.bind(body.msisdn(), body.publicKey(), body.otp(), body.encryptedPin(), SignedRequests.from(request));
  }
}
