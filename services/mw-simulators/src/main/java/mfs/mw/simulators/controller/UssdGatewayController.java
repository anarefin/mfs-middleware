package mfs.mw.simulators.controller;

import jakarta.validation.Valid;
import mfs.mw.simulators.dto.UssdDialRequest;
import mfs.mw.simulators.dto.UssdDialResult;
import mfs.mw.simulators.service.UssdGatewayService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Control API of the USSD gateway simulator (HTTP mode): plays one whole session. */
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
}
