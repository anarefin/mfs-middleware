package mfs.mw.core.mock.controller;

import jakarta.validation.Valid;
import java.util.List;
import mfs.mw.core.mock.dto.MockParty;
import mfs.mw.core.mock.dto.MockScenario;
import mfs.mw.core.mock.dto.MockSettings;
import mfs.mw.core.mock.dto.MockSwitch;
import mfs.mw.core.mock.dto.RecordedRequest;
import mfs.mw.core.mock.dto.ResetRequest;
import mfs.mw.core.mock.dto.ResetResult;
import mfs.mw.core.mock.exception.ErrorCodes;
import mfs.mw.core.mock.exception.MwException;
import mfs.mw.core.mock.service.mockcontrol.CoreReset;
import mfs.mw.core.mock.service.mockcontrol.Journal;
import mfs.mw.core.mock.service.mockcontrol.MockControl;
import mfs.mw.core.mock.service.mockcontrol.MockParties;
import mfs.mw.core.mock.service.mockcontrol.PartySwitch;
import mfs.mw.core.mock.service.mockcontrol.ScenarioEngine;
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
 * The mock-control API of the core party (ADR D33, FRS MCT-01..03, MCT-06, MCT-07; ticket 103; contract
 * {@code api/mw-core-mock/core-mock-control.v1.openapi.yaml}): the same paths as mw-simulators', plus the
 * global reset of this service. Only in the {@value MockControl#PROFILE} profile.
 */
@RestController
@Profile(MockControl.PROFILE)
public class MockControlController {

  private final ScenarioEngine engine;
  private final PartySwitch switches;
  private final Journal journal;
  private final CoreReset reset;

  public MockControlController(ScenarioEngine engine, PartySwitch switches, Journal journal, CoreReset reset) {
    this.engine = engine;
    this.switches = switches;
    this.journal = journal;
    this.reset = reset;
  }

  @GetMapping("/mock-control/parties")
  List<MockParty> parties() {
    return MockParties.ALL;
  }

  /** MCT-07: ledger back to the seed (optionally another seed version), scenarios, switch and journal cleared. */
  @PostMapping("/mock-control/reset")
  ResetResult resetAll(@Valid @RequestBody(required = false) ResetRequest body) {
    return reset.all(body == null ? null : body.seedVersion());
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

  /** Clears the journal and the party's control state (MCT-06); the ledger stays (that is the global reset). */
  @DeleteMapping("/mock-control/{party}/journal")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void resetParty(@PathVariable String party) {
    reset.party(party);
  }
}
