package mfs.mw.simulators.controller;

import java.util.Map;
import mfs.mw.simulators.dto.BankProfile;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.BankGatewayService;
import mfs.mw.simulators.service.BankGatewaySoapService;
import mfs.mw.simulators.util.SoapXml;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The simulated bank gateway (ticket 117d): REST banks under {@code /bank/{code}/v1/...}, SOAP banks at
 * {@code /bank/{code}/v1/soap}, the health probe of both, and the test helper {@code GET
 * /sim/bank/{code}/accounts/{account}}. See {@link BankGatewayService}.
 */
@RestController
public class BankGatewayController {

  private static final MediaType TEXT_XML_UTF8 = MediaType.parseMediaType("text/xml;charset=UTF-8");

  private final BankGatewayService service;
  private final BankGatewaySoapService soap;

  public BankGatewayController(BankGatewayService service, BankGatewaySoapService soap) {
    this.service = service;
    this.soap = soap;
  }

  @GetMapping("/bank/{code}/v1/health")
  ResponseEntity<Object> health(@PathVariable String code, @RequestHeader HttpHeaders headers) {
    BankProfile p = service.profile(code, null);
    boolean authed = p.protocol() == BankProfile.Protocol.SOAP && !"basic".equals(p.auth())
        || BankGatewayService.httpAuthPresent(p, headers::getFirst);
    return toResponse(authed ? service.health(p) : BankGatewayService.unauthenticated());
  }

  @PostMapping("/bank/{code}/v1/accounts/verify")
  ResponseEntity<Object> verify(
      @PathVariable String code, @RequestHeader HttpHeaders headers, @RequestBody Map<String, Object> body) {
    BankProfile p = service.profile(code, BankProfile.Protocol.REST);
    return toResponse(BankGatewayService.httpAuthPresent(p, headers::getFirst)
        ? service.verify(p, text(body, "accountNumber"))
        : BankGatewayService.unauthenticated());
  }

  @PostMapping("/bank/{code}/v1/transfers")
  ResponseEntity<Object> transfer(
      @PathVariable String code, @RequestHeader HttpHeaders headers, @RequestBody Map<String, Object> body) {
    BankProfile p = service.profile(code, BankProfile.Protocol.REST);
    return toResponse(BankGatewayService.httpAuthPresent(p, headers::getFirst)
        ? service.transfer(p, text(body, "transactionId"), text(body, "accountNumber"), text(body, "amount"),
            text(body, "direction"))
        : BankGatewayService.unauthenticated());
  }

  @GetMapping("/bank/{code}/v1/transfers/{reference}")
  ResponseEntity<Object> status(
      @PathVariable String code, @PathVariable String reference, @RequestHeader HttpHeaders headers) {
    BankProfile p = service.profile(code, BankProfile.Protocol.REST);
    return toResponse(BankGatewayService.httpAuthPresent(p, headers::getFirst)
        ? service.status(p, reference)
        : BankGatewayService.unauthenticated());
  }

  @PostMapping("/bank/{code}/v1/transfers/{reference}/reverse")
  ResponseEntity<Object> reverse(
      @PathVariable String code,
      @PathVariable String reference,
      @RequestHeader HttpHeaders headers,
      @RequestBody(required = false) Map<String, Object> body) {
    BankProfile p = service.profile(code, BankProfile.Protocol.REST);
    return toResponse(BankGatewayService.httpAuthPresent(p, headers::getFirst)
        ? service.reverse(p, reference, body == null ? null : text(body, "reversalId"))
        : BankGatewayService.unauthenticated());
  }

  @PostMapping(value = "/bank/{code}/v1/soap", consumes = {MediaType.TEXT_XML_VALUE, "application/soap+xml"})
  ResponseEntity<String> soap(
      @PathVariable String code,
      @RequestHeader HttpHeaders headers,
      @RequestBody(required = false) String envelope) {
    SoapXml.SoapReply reply = soap.handle(service.profile(code, BankProfile.Protocol.SOAP), headers::getFirst, envelope);
    return ResponseEntity.status(reply.status()).contentType(TEXT_XML_UTF8).body(reply.envelope());
  }

  @GetMapping("/sim/bank/{code}/accounts/{account}")
  Map<String, Object> account(@PathVariable String code, @PathVariable String account) {
    return service.account(code, account);
  }

  private static String text(Map<String, Object> body, String field) {
    Object v = body.get(field);
    return v == null ? null : v.toString();
  }

  private static ResponseEntity<Object> toResponse(SimReply reply) {
    return ResponseEntity.status(reply.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
  }
}
