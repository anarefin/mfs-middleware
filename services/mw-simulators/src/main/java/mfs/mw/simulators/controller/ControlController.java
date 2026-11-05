package mfs.mw.simulators.controller;

import jakarta.validation.Valid;
import java.util.List;
import mfs.mw.simulators.dto.RecordedRequest;
import mfs.mw.simulators.dto.ReplayRequest;
import mfs.mw.simulators.dto.ReplayResult;
import mfs.mw.simulators.dto.Scenario;
import mfs.mw.simulators.service.CallbackService;
import mfs.mw.simulators.service.RequestRecorder;
import mfs.mw.simulators.service.ScenarioStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Control API shared by every simulator (spec 22.2): scenario, recorded requests, callback replay. */
@RestController
public class ControlController {

  private final ScenarioStore scenarios;
  private final RequestRecorder recorder;
  private final CallbackService callbacks;

  public ControlController(
      ScenarioStore scenarios, RequestRecorder recorder, CallbackService callbacks) {
    this.scenarios = scenarios;
    this.recorder = recorder;
    this.callbacks = callbacks;
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
  List<RecordedRequest> requests(
      @PathVariable String partner, @RequestParam(required = false) String path) {
    scenarios.require(partner);
    return recorder.list(partner, path);
  }

  @DeleteMapping("/sim/{partner}/requests")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void clearRequests(@PathVariable String partner) {
    scenarios.require(partner);
    recorder.clear(partner);
  }

  @PostMapping("/sim/{partner}/callbacks/{reference}/replay")
  ReplayResult replay(
      @PathVariable String partner,
      @PathVariable String reference,
      @RequestBody(required = false) ReplayRequest body) {
    int status = callbacks.replay(partner, reference, body == null ? null : body.callbackUrl());
    return new ReplayResult(reference, status);
  }
}
