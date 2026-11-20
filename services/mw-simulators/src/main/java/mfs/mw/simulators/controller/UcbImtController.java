package mfs.mw.simulators.controller;

import jakarta.validation.Valid;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.dto.UcbImtRequests;
import mfs.mw.simulators.service.UcbImtService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * UCB IMT mock (ticket 119): {@code POST /mock-control/ucb-imt/trigger} pushes a signed credit to
 * mw-adapter-remit, and {@code /ucb-imt/v1/remittances} is the confirmation and status wire that
 * adapter calls back.
 */
@RestController
public class UcbImtController {

  private final UcbImtService service;

  public UcbImtController(UcbImtService service) {
    this.service = service;
  }

  @PostMapping("/mock-control/ucb-imt/trigger")
  UcbImtRequests.TriggerResult trigger(@Valid @RequestBody UcbImtRequests.Trigger request) {
    return service.trigger(request);
  }

  @PostMapping("/ucb-imt/v1/remittances/{reference}/confirmation")
  ResponseEntity<Object> confirm(
      @PathVariable String reference,
      @Valid @RequestBody UcbImtRequests.Confirmation body,
      @RequestHeader(value = "X-Signature", required = false) String signature,
      @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
      @RequestHeader(value = "X-Nonce", required = false) String nonce) {
    return toResponse(service.confirm(reference, body, signed(signature, timestamp, nonce)));
  }

  @GetMapping("/ucb-imt/v1/remittances/{reference}")
  ResponseEntity<Object> status(
      @PathVariable String reference,
      @RequestHeader(value = "X-Signature", required = false) String signature,
      @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
      @RequestHeader(value = "X-Nonce", required = false) String nonce) {
    return toResponse(service.status(reference, signed(signature, timestamp, nonce)));
  }

  private static boolean signed(String signature, String timestamp, String nonce) {
    return signature != null && !signature.isBlank()
        && timestamp != null && !timestamp.isBlank()
        && nonce != null && !nonce.isBlank();
  }

  private static ResponseEntity<Object> toResponse(SimReply reply) {
    return ResponseEntity.status(reply.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
  }
}
