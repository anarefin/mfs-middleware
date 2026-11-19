package mfs.mw.simulators.controller;

import jakarta.validation.Valid;
import java.util.List;
import mfs.mw.simulators.dto.MockParty;
import mfs.mw.simulators.dto.MockScenario;
import mfs.mw.simulators.dto.MockSettings;
import mfs.mw.simulators.dto.MockSwitch;
import mfs.mw.simulators.dto.RecordedRequest;
import mfs.mw.simulators.dto.ReplayRequest;
import mfs.mw.simulators.dto.ReplayResult;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.service.CallbackService;
import mfs.mw.simulators.service.mockcontrol.Journal;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.MockParties;
import mfs.mw.simulators.service.mockcontrol.PartyReset;
import mfs.mw.simulators.service.mockcontrol.PartySwitch;
import mfs.mw.simulators.service.mockcontrol.ScenarioEngine;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one mock-control API (ADR D33, FRS MCT-01..03, MCT-06; contract {@code
 * api/mw-simulators/mock-control.v1.openapi.yaml}). Only in the {@value MockControl#PROFILE} profile.
 */
@RestController
@Profile(MockControl.PROFILE)
public class MockControlController {

  private final ScenarioEngine engine;
  private final PartySwitch switches;
  private final Journal journal;
  private final PartyReset reset;
  private final CallbackService callbacks;

  public MockControlController(
      ScenarioEngine engine, PartySwitch switches, Journal journal, PartyReset reset, CallbackService callbacks) {
    this.engine = engine;
    this.switches = switches;
    this.journal = journal;
    this.reset = reset;
    this.callbacks = callbacks;
  }

  @GetMapping("/mock-control/parties")
  List<MockParty> parties() {
    return MockParties.ALL;
  }

  @PostMapping("/mock-control/{party}/scenarios")
  @ResponseStatus(HttpStatus.CREATED)
  MockScenario add(@PathVariable String party, @Valid @RequestBody MockScenario scenario) {
    return engine.add(party, scenario);
  }

  @GetMapping("/mock-control/{party}/scenarios")
  List<MockScenario> scenarios(@PathVariable String party) {
    return engine.list(party);
  }

  @DeleteMapping("/mock-control/{party}/scenarios")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void clear(@PathVariable String party) {
    engine.clear(party);
  }

  @DeleteMapping("/mock-control/{party}/scenarios/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void remove(@PathVariable String party, @PathVariable String id) {
    if (!engine.remove(party, id)) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "No scenario " + id).withStatus(HttpStatus.NOT_FOUND);
    }
  }

  @PutMapping("/mock-control/{party}/switch")
  MockSwitch setSwitch(@PathVariable String party, @Valid @RequestBody MockSwitch body) {
    switches.set(party, body);
    return switches.get(party);
  }

  @GetMapping("/mock-control/{party}/switch")
  MockSwitch getSwitch(@PathVariable String party) {
    return switches.get(party);
  }

  @PutMapping("/mock-control/{party}/settings")
  MockSettings setSettings(@PathVariable String party, @Valid @RequestBody MockSettings body) {
    switches.settings(party, body);
    return switches.settings(party);
  }

  @GetMapping("/mock-control/{party}/settings")
  MockSettings getSettings(@PathVariable String party) {
    return switches.settings(party);
  }

  @GetMapping("/mock-control/{party}/journal")
  List<RecordedRequest> journal(
      @PathVariable String party,
      @RequestParam(required = false) String path,
      @RequestParam(required = false) String contains,
      @RequestParam(required = false) Long afterSeq) {
    return journal.query(party, path, contains, afterSeq);
  }

  /** Clears the journal and resets the party's state (MCT-06). */
  @DeleteMapping("/mock-control/{party}/journal")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void resetParty(@PathVariable String party) {
    reset.reset(party);
  }

  @PostMapping("/mock-control/{party}/callbacks/{reference}/replay")
  ReplayResult replay(
      @PathVariable String party, @PathVariable String reference, @RequestBody(required = false) ReplayRequest body) {
    return new ReplayResult(reference, callbacks.replay(party, reference, body == null ? null : body.callbackUrl()));
  }
}
