package mfs.mw.simulators.controller;

import jakarta.validation.Valid;
import java.util.List;
import mfs.mw.simulators.dto.BillFetchRequest;
import mfs.mw.simulators.dto.BillPaymentRequest;
import mfs.mw.simulators.dto.Biller;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.BillAggregatorService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Generic utility bill aggregator wire contract: billers, bill fetch, payment, status inquiry. */
@RestController
public class BillAggregatorController {

  private final BillAggregatorService service;

  public BillAggregatorController(BillAggregatorService service) {
    this.service = service;
  }

  @GetMapping("/billagg/v1/billers")
  List<Biller> billers() {
    return service.billers();
  }

  @PostMapping("/billagg/v1/bills/fetch")
  ResponseEntity<Object> fetch(@Valid @RequestBody BillFetchRequest request) {
    return toResponse(service.fetch(request));
  }

  @PostMapping("/billagg/v1/payments")
  ResponseEntity<Object> pay(@Valid @RequestBody BillPaymentRequest request) {
    return toResponse(service.pay(request));
  }

  @GetMapping("/billagg/v1/payments/{transactionId}")
  ResponseEntity<Object> status(@PathVariable String transactionId) {
    return toResponse(service.status(transactionId));
  }

  private static ResponseEntity<Object> toResponse(SimReply reply) {
    return ResponseEntity.status(reply.httpStatus())
        .contentType(MediaType.APPLICATION_JSON)
        .body(reply.body());
  }
}
