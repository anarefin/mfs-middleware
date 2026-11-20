package mfs.mw.simulators.controller;

import java.util.Map;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.OperatorOcsProfile;
import mfs.mw.simulators.service.OperatorOcsService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Four operator OCS profiles (ticket 112a). Each path is that operator's wire, not the legacy
 * {@code /ocs/v1} contract. Teletalk's token endpoint is the OAuth2 client-credentials target.
 */
@RestController
public class OperatorOcsController {

  private final OperatorOcsService service;

  public OperatorOcsController(OperatorOcsService service) {
    this.service = service;
  }

  @PostMapping("/ocs-gp/v1/recharge")
  ResponseEntity<Object> gpRecharge(@RequestBody Map<String, Object> body) {
    return toResponse(service.recharge(OperatorOcsProfile.GP, body));
  }

  @PostMapping("/ocs-gp/v1/bills")
  ResponseEntity<Object> gpBill(@RequestBody Map<String, Object> body) {
    return toResponse(service.bill(OperatorOcsProfile.GP, body));
  }

  @PostMapping("/ocs-gp/v1/billpay")
  ResponseEntity<Object> gpPay(@RequestBody Map<String, Object> body) {
    return toResponse(service.payBill(OperatorOcsProfile.GP, body));
  }

  @GetMapping("/ocs-gp/v1/recharge/{transactionId}")
  ResponseEntity<Object> gpStatus(@PathVariable String transactionId) {
    return toResponse(service.status(OperatorOcsProfile.GP, transactionId));
  }

  @PostMapping("/ocs-gp/v1/recharge/{transactionId}/reverse")
  ResponseEntity<Object> gpReverse(@PathVariable String transactionId, @RequestBody(required = false) Map<String, Object> body) {
    return toResponse(service.reverse(OperatorOcsProfile.GP, transactionId, body == null ? Map.of() : body));
  }

  @PostMapping("/ocs-robi/v1/topup")
  ResponseEntity<Object> robiRecharge(@RequestBody Map<String, Object> body) {
    return toResponse(service.recharge(OperatorOcsProfile.ROBI, body));
  }

  @PostMapping("/ocs-robi/v1/invoice")
  ResponseEntity<Object> robiBill(@RequestBody Map<String, Object> body) {
    return toResponse(service.bill(OperatorOcsProfile.ROBI, body));
  }

  @PostMapping("/ocs-robi/v1/settle")
  ResponseEntity<Object> robiPay(@RequestBody Map<String, Object> body) {
    return toResponse(service.payBill(OperatorOcsProfile.ROBI, body));
  }

  @GetMapping("/ocs-robi/v1/topup/{transactionId}")
  ResponseEntity<Object> robiStatus(@PathVariable String transactionId) {
    return toResponse(service.status(OperatorOcsProfile.ROBI, transactionId));
  }

  @PostMapping("/ocs-robi/v1/topup/{transactionId}/reverse")
  ResponseEntity<Object> robiReverse(@PathVariable String transactionId, @RequestBody(required = false) Map<String, Object> body) {
    return toResponse(service.reverse(OperatorOcsProfile.ROBI, transactionId, body == null ? Map.of() : body));
  }

  @PostMapping("/ocs-teletalk/oauth/token")
  ResponseEntity<Object> teletalkToken() {
    return toResponse(service.token());
  }

  @PostMapping("/ocs-teletalk/v1/recharge")
  ResponseEntity<Object> teletalkRecharge(@RequestBody Map<String, Object> body) {
    return toResponse(service.recharge(OperatorOcsProfile.TELETALK, body));
  }

  @PostMapping("/ocs-teletalk/v1/bill")
  ResponseEntity<Object> teletalkBill(@RequestBody Map<String, Object> body) {
    return toResponse(service.bill(OperatorOcsProfile.TELETALK, body));
  }

  @PostMapping("/ocs-teletalk/v1/pay")
  ResponseEntity<Object> teletalkPay(@RequestBody Map<String, Object> body) {
    return toResponse(service.payBill(OperatorOcsProfile.TELETALK, body));
  }

  @GetMapping("/ocs-teletalk/v1/recharge/{transactionId}")
  ResponseEntity<Object> teletalkStatus(@PathVariable String transactionId) {
    return toResponse(service.status(OperatorOcsProfile.TELETALK, transactionId));
  }

  @PostMapping("/ocs-teletalk/v1/recharge/{transactionId}/reverse")
  ResponseEntity<Object> teletalkReverse(
      @PathVariable String transactionId, @RequestBody(required = false) Map<String, Object> body) {
    return toResponse(service.reverse(OperatorOcsProfile.TELETALK, transactionId, body == null ? Map.of() : body));
  }

  @PostMapping("/ocs-bl/v1/reload")
  ResponseEntity<Object> blRecharge(@RequestBody Map<String, Object> body) {
    return toResponse(service.recharge(OperatorOcsProfile.BL, body));
  }

  @PostMapping("/ocs-bl/v1/due")
  ResponseEntity<Object> blBill(@RequestBody Map<String, Object> body) {
    return toResponse(service.bill(OperatorOcsProfile.BL, body));
  }

  @PostMapping("/ocs-bl/v1/paybill")
  ResponseEntity<Object> blPay(@RequestBody Map<String, Object> body) {
    return toResponse(service.payBill(OperatorOcsProfile.BL, body));
  }

  @GetMapping("/ocs-bl/v1/reload/{transactionId}")
  ResponseEntity<Object> blStatus(@PathVariable String transactionId) {
    return toResponse(service.status(OperatorOcsProfile.BL, transactionId));
  }

  @PostMapping("/ocs-bl/v1/reload/{transactionId}/reverse")
  ResponseEntity<Object> blReverse(@PathVariable String transactionId, @RequestBody(required = false) Map<String, Object> body) {
    return toResponse(service.reverse(OperatorOcsProfile.BL, transactionId, body == null ? Map.of() : body));
  }

  private static ResponseEntity<Object> toResponse(SimReply reply) {
    return ResponseEntity.status(reply.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
  }
}
