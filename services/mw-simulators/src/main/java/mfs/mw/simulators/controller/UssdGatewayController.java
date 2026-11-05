package mfs.mw.simulators.controller;

import jakarta.validation.Valid;
import mfs.mw.simulators.dto.UssdDialRequest;
import mfs.mw.simulators.dto.UssdDialResult;
import mfs.mw.simulators.dto.UssdSmppBinds;
import mfs.mw.simulators.dto.UssdSmppScenario;
import mfs.mw.simulators.service.UssdGatewayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Control API of the USSD gateway simulator: plays one whole session (HTTP or SMPP mode), SMPP misbehaviour. */
@RestController
public class UssdGatewayController {

  private final UssdGatewayService service;

  public UssdGatewayController(UssdGatewayService service) {
    this.service = service;
  }

  @PostMapping("/sim/ussdgw/sessions")
  public UssdDialResult dial(@Valid @RequestBody UssdDialRequest body) {
    return service.dial(body);
  }

  @PostMapping("/sim/ussdgw/smpp/scenario")
  public ResponseEntity<Void> smppScenario(@RequestBody UssdSmppScenario body) {
    service.smppScenario(body);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/sim/ussdgw/smpp/drop")
  public ResponseEntity<Void> dropSmppBinds() {
    service.dropSmppBinds();
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/sim/ussdgw/smpp/binds")
  public UssdSmppBinds smppBinds() {
    return service.smppBinds();
  }
}
