package mfs.mw.simulators.controller;

import jakarta.validation.Valid;
import java.util.Map;
import mfs.mw.simulators.dto.PayShieldPinBlockRequest;
import mfs.mw.simulators.dto.PayShieldScenario;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.service.PayShieldScenarioStore;
import mfs.mw.simulators.service.PayShieldService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scenario control of the payShield simulator (ticket 64): {@code POST|GET|DELETE
 * /sim/payshield/scenario} {@code {latencyMs, errorCode, outage, commands}}; a POST replaces the
 * scenario. Test helper {@code POST /sim/payshield/pinblock {keyType, key, format, pin, pan}} ->
 * {@code {block}}: the PIN block an ATM or switch sharing that key would send. Not a partner in
 * {@code sim.partners} (no recording: commands carry PIN blocks, the helper a PIN).
 */
@RestController
public class PayShieldController {

  private final PayShieldScenarioStore scenario;
  private final PayShieldService service;

  public PayShieldController(PayShieldScenarioStore scenario, PayShieldService service) {
    this.scenario = scenario;
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

  @PostMapping("/sim/payshield/scenario")
  PayShieldScenario set(@RequestBody PayShieldScenario body) {
    scenario.set(body);
    return scenario.get();
  }

  @GetMapping("/sim/payshield/scenario")
  PayShieldScenario get() {
    return scenario.get();
  }

  @DeleteMapping("/sim/payshield/scenario")
  ResponseEntity<Void> reset() {
    scenario.reset();
    return ResponseEntity.noContent().build();
  }
}
