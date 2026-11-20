package mfs.mw.simulators.controller;

import jakarta.validation.Valid;
import java.util.Map;
import mfs.mw.simulators.dto.PayShieldPinBlockRequest;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.service.PayShieldService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test helper of the payShield simulator (ticket 64; its misbehaviour is the mock party {@code payshield},
 * ticket 102b, the old {@code /sim/payshield/scenario} an alias in {@code ControlController}): {@code POST /sim/payshield/pinblock {keyType, key, format, pin, pan}} ->
 * {@code {block}}: the PIN block an ATM or switch sharing that key would send (never recorded: it
 * carries a PIN).
 */
@RestController
public class PayShieldController {

  private final PayShieldService service;

  public PayShieldController(PayShieldService service) {
    this.service = service;
  }

  @PostMapping("/sim/payshield/pinblock")
  Map<String, String> pinBlock(@Valid @RequestBody PayShieldPinBlockRequest r) {
    try {
      return Map.of("block", service.pinBlock(r.keyType(), r.key(), r.format(), r.pin(), r.pan()));
    } catch (IllegalArgumentException e) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Key or PIN block format not usable");
    }
  }

}
