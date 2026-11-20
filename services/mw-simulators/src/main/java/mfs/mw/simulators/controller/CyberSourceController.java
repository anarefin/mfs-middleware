package mfs.mw.simulators.controller;

import jakarta.validation.Valid;
import mfs.mw.simulators.dto.CyberSourceRequests;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.CyberSourceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CyberSource-style card acquirer wire contract (ticket 118): sessions, the hosted page, authorise +
 * capture, status and refund. Mock party {@code cybersource} on {@code /mock-control/cybersource}.
 */
@RestController
public class CyberSourceController {

  private final CyberSourceService service;

  public CyberSourceController(CyberSourceService service) {
    this.service = service;
  }

  @PostMapping("/cybersource/v1/sessions")
  ResponseEntity<Object> session(@Valid @RequestBody CyberSourceRequests.Session request) {
    return toResponse(service.createSession(request));
  }

  @GetMapping(value = "/cybersource/hp/{sessionId}", produces = MediaType.TEXT_HTML_VALUE)
  ResponseEntity<String> hostedPage(@PathVariable String sessionId) {
    return service.hostedPage(sessionId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("<p>Unknown session</p>"));
  }

  @PostMapping(value = "/cybersource/hp/{sessionId}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  ResponseEntity<Object> submitCard(@PathVariable String sessionId, @RequestParam String cardNumber) {
    return service.submitCard(sessionId, cardNumber)
        .<ResponseEntity<Object>>map(b -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(b))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }

  @PostMapping("/cybersource/v1/payments")
  ResponseEntity<Object> capture(@Valid @RequestBody CyberSourceRequests.Payment request) {
    return toResponse(service.capture(request));
  }

  @GetMapping("/cybersource/v1/payments/{clientReference}")
  ResponseEntity<Object> status(@PathVariable String clientReference) {
    return toResponse(service.status(clientReference));
  }

  @PostMapping("/cybersource/v1/payments/{clientReference}/refunds")
  ResponseEntity<Object> refund(
      @PathVariable String clientReference, @Valid @RequestBody CyberSourceRequests.Refund request) {
    return toResponse(service.refund(clientReference, request));
  }

  private static ResponseEntity<Object> toResponse(SimReply reply) {
    return ResponseEntity.status(reply.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
  }
}
