package mfs.mw.simulators.controller;

import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.NpsbService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * NPSB switch mock (tickets 121a, 121b part 2): participant list, the ISO 20022 transfers, status
 * inquiries and returns ({@code {messageType, document}} in, {@code {msgId, document}} out, the wire of
 * mw-adapter-interop's {@code npsb} seed) and the inbound push. Mock party {@code npsb} on
 * {@code /mock-control/npsb}.
 */
@RestController
public class NpsbController {

  /** The adapter's request body: the ISO message name and the document. */
  public record IsoEnvelope(String messageType, String document) {}

  private final NpsbService service;

  public NpsbController(NpsbService service) {
    this.service = service;
  }

  @GetMapping("/npsb/v1/participants")
  ResponseEntity<Object> participants() {
    return json(service.participants());
  }

  @PostMapping("/npsb/v1/transfers")
  ResponseEntity<Object> transfer(@RequestBody IsoEnvelope body) {
    return json(service.transfer(body.document()));
  }

  @PostMapping("/npsb/v1/status")
  ResponseEntity<Object> status(@RequestBody IsoEnvelope body) {
    return json(service.status(body.document()));
  }

  @PostMapping("/npsb/v1/returns")
  ResponseEntity<Object> returns(@RequestBody IsoEnvelope body) {
    return json(service.returnTransfer(body.document()));
  }

  /** Test helper: the switch pushes a pacs.008 to the middleware's inbound endpoint. */
  @PostMapping("/mock-control/npsb/inbound")
  NpsbService.InboundResult inbound(@RequestBody NpsbService.InboundPush body) {
    return service.inbound(body);
  }

  private static ResponseEntity<Object> json(SimReply reply) {
    return ResponseEntity.status(reply.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
  }
}
