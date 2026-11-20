package mfs.mw.simulators.controller;

import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.NpsbService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** NPSB switch participant list (ticket 121a). Mock party {@code npsb} on {@code /mock-control/npsb}. */
@RestController
public class NpsbController {

  private final NpsbService service;

  public NpsbController(NpsbService service) {
    this.service = service;
  }

  @GetMapping("/npsb/v1/participants")
  ResponseEntity<Object> participants() {
    SimReply reply = service.participants();
    return ResponseEntity.status(reply.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
  }
}
