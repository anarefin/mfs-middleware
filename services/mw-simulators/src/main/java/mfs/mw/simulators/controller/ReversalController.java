package mfs.mw.simulators.controller;

import java.util.Map;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.ReversalService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reverse wire contract of the simulated partners (ticket 97): {@code POST
 * /ocs/v1/topup/{transactionId}/reverse}, {@code /billagg/v1/payments/{transactionId}/reverse},
 * {@code /c2wbank/v1/debits/{transactionId}/reverse}, optional body {@code {reversalId}} (the
 * adapters send {@code reverse:<txnId>}). Answers {@code {transactionId, status REVERSED|NOT_FOUND|
 * FAILED|UNAVAILABLE, code, message, reversalRef?, reversalId?}}.
 */
@RestController
public class ReversalController {

  private final ReversalService service;

  public ReversalController(ReversalService service) {
    this.service = service;
  }

  @PostMapping("/ocs/v1/topup/{transactionId}/reverse")
  ResponseEntity<Object> ocs(@PathVariable String transactionId, @RequestBody(required = false) Map<String, Object> body) {
    return toResponse(service.reverse("ocs", transactionId, reversalId(body)));
  }

  @PostMapping("/billagg/v1/payments/{transactionId}/reverse")
  ResponseEntity<Object> billagg(@PathVariable String transactionId, @RequestBody(required = false) Map<String, Object> body) {
    return toResponse(service.reverse("billagg", transactionId, reversalId(body)));
  }

  @PostMapping("/c2wbank/v1/debits/{transactionId}/reverse")
  ResponseEntity<Object> c2wbank(@PathVariable String transactionId, @RequestBody(required = false) Map<String, Object> body) {
    return toResponse(service.reverse("c2wbank", transactionId, reversalId(body)));
  }

  private static String reversalId(Map<String, Object> body) {
    return body != null && body.get("reversalId") instanceof String s && !s.isBlank() ? s : null;
  }

  private static ResponseEntity<Object> toResponse(SimReply reply) {
    return ResponseEntity.status(reply.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
  }
}
