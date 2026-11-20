package mfs.mw.simulators.controller;

import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.UcbAtmService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** UCB ATM dispense status (ticket 120a). Mock party {@code ucbatm} on {@code /mock-control/ucbatm}. */
@RestController
public class UcbAtmController {

  private final UcbAtmService service;

  public UcbAtmController(UcbAtmService service) {
    this.service = service;
  }

  @GetMapping("/ucbatm/v1/dispenses/{reference}")
  ResponseEntity<Object> status(@PathVariable String reference) {
    SimReply reply = service.dispenseStatus(reference);
    return ResponseEntity.status(reply.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
  }
}
