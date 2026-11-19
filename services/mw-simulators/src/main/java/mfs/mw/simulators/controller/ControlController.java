package mfs.mw.simulators.controller;

import jakarta.validation.Valid;
import java.util.List;
import mfs.mw.simulators.dto.MerchantIpnScenario;
import mfs.mw.simulators.dto.PayShieldScenario;
import mfs.mw.simulators.dto.RecordedRequest;
import mfs.mw.simulators.dto.ReplayRequest;
import mfs.mw.simulators.dto.ReplayResult;
import mfs.mw.simulators.dto.Scenario;
import mfs.mw.simulators.dto.UssdSmppScenario;
import mfs.mw.simulators.service.CallbackService;
import mfs.mw.simulators.service.MerchantIpnService;
import mfs.mw.simulators.service.mockcontrol.Journal;
import mfs.mw.simulators.service.mockcontrol.LegacyScenarios;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The old control API (spec 22.2), deprecated by ADR D33 and kept as aliases of the mock-control engine
 * for one release ({@link LegacyScenarios} translates): scenario, recorded requests, callback replay, and
 * the merchant IPN receiver's scenario and reset, the payShield and SMPP USSD gateway scenarios (ticket
 * 102b). Only in the {@value MockControl#PROFILE} profile.
 */
@RestController
@Profile(MockControl.PROFILE)
public class ControlController {

  private final LegacyScenarios scenarios;
  private final Journal journal;
  private final CallbackService callbacks;
  private final MerchantIpnService merchant;

  public ControlController(
      LegacyScenarios scenarios, Journal journal, CallbackService callbacks, MerchantIpnService merchant) {
    this.scenarios = scenarios;
    this.journal = journal;
    this.callbacks = callbacks;
    this.merchant = merchant;
  }

  @PostMapping("/sim/{partner}/scenario")
  Scenario setScenario(@PathVariable String partner, @Valid @RequestBody Scenario scenario) {
    scenarios.set(partner, scenario);
    return scenario;
  }

  @GetMapping("/sim/{partner}/scenario")
  Scenario getScenario(@PathVariable String partner) {
    return scenarios.get(partner);
  }

  @DeleteMapping("/sim/{partner}/scenario")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void resetScenario(@PathVariable String partner) {
    scenarios.reset(partner);
  }

  @GetMapping("/sim/{partner}/requests")
  List<RecordedRequest> requests(@PathVariable String partner, @RequestParam(required = false) String path) {
    scenarios.get(partner);
    return journal.query(partner, path, null, null);
  }

  @DeleteMapping("/sim/{partner}/requests")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void clearRequests(@PathVariable String partner) {
    scenarios.get(partner);
    journal.clear(partner);
  }

  @PostMapping("/sim/{partner}/callbacks/{reference}/replay")
  ReplayResult replay(
      @PathVariable String partner,
      @PathVariable String reference,
      @RequestBody(required = false) ReplayRequest body) {
    int status = callbacks.replay(partner, reference, body == null ? null : body.callbackUrl());
    return new ReplayResult(reference, status);
  }

  @PostMapping("/sim/merchant/ipn/scenario")
  MerchantIpnScenario merchantScenario(@Valid @RequestBody MerchantIpnScenario scenario) {
    scenarios.merchant(scenario);
    return scenario;
  }

  @PostMapping("/sim/payshield/scenario")
  PayShieldScenario setPayShield(@RequestBody PayShieldScenario body) {
    scenarios.payShield(body);
    return scenarios.payShield();
  }

  @GetMapping("/sim/payshield/scenario")
  PayShieldScenario getPayShield() {
    return scenarios.payShield();
  }

  @DeleteMapping("/sim/payshield/scenario")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void resetPayShield() {
    scenarios.forget("payshield");
  }

  @PostMapping("/sim/ussdgw/smpp/scenario")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void ussdSmppScenario(@RequestBody UssdSmppScenario body) {
    scenarios.ussd(body);
  }

  /** Resets the merchant receiver: what it received and its scenarios. */
  @DeleteMapping("/sim/merchant/ipn/received")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void merchantReset() {
    merchant.reset();
    scenarios.forget(MerchantIpnService.PARTY);
  }
}
