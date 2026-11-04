package mfs.mw.simulators.controller;

import jakarta.validation.Valid;
import mfs.mw.simulators.dto.BankDebitRequest;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.BankC2wService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Generic C2W bank wire contract (ticket 23): debit a linked account, status inquiry. */
@RestController
public class BankC2wController {

  private final BankC2wService service;

  public BankC2wController(BankC2wService service) {
    this.service = service;
  }

  @PostMapping("/c2wbank/v1/debits")
  ResponseEntity<Object> debit(@Valid @RequestBody BankDebitRequest request) {
    return toResponse(service.debit(request));
  }

  @GetMapping("/c2wbank/v1/debits/{transactionId}")
  ResponseEntity<Object> status(@PathVariable String transactionId) {
    return toResponse(service.status(transactionId));
  }

  private static ResponseEntity<Object> toResponse(SimReply reply) {
    return ResponseEntity.status(reply.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
  }
}
