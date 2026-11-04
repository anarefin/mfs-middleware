package mfs.mw.simulators.controller;

import jakarta.validation.Valid;
import mfs.mw.simulators.dto.OcsReply;
import mfs.mw.simulators.dto.OcsTopupRequest;
import mfs.mw.simulators.service.OcsService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Generic prepaid OCS wire contract: top-up and status inquiry. */
@RestController
public class OcsController {

  private final OcsService service;

  public OcsController(OcsService service) {
    this.service = service;
  }

  @PostMapping("/ocs/v1/topup")
  ResponseEntity<Object> topup(@Valid @RequestBody OcsTopupRequest request) {
    return toResponse(service.topup(request));
  }

  @GetMapping("/ocs/v1/topup/{transactionId}")
  ResponseEntity<Object> status(@PathVariable String transactionId) {
    return toResponse(service.statusReply(transactionId));
  }

  private static ResponseEntity<Object> toResponse(OcsReply reply) {
    return ResponseEntity.status(reply.httpStatus())
        .contentType(MediaType.APPLICATION_JSON)
        .body(reply.body());
  }
}
