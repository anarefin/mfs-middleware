package mfs.mw.simulators.controller;

import mfs.mw.simulators.service.SoapBillerService;
import mfs.mw.simulators.util.SoapXml;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SOAP stub biller {@code soapbill} (ticket 85): {@code POST /soapbill/v1} (SOAP 1.1, {@code
 * text/xml}); control {@code POST /sim/soapbill/fault {faultCode: Client|Server, code, reason}}
 * forces a fault on every call, {@code DELETE} clears it. See {@link SoapBillerService}.
 */
@RestController
public class SoapBillerController {

  private static final MediaType TEXT_XML_UTF8 = MediaType.parseMediaType("text/xml;charset=UTF-8");

  private final SoapBillerService service;

  public SoapBillerController(SoapBillerService service) {
    this.service = service;
  }

  @PostMapping(value = "/soapbill/v1", consumes = {MediaType.TEXT_XML_VALUE, "application/soap+xml"})
  ResponseEntity<String> soap(@RequestBody(required = false) String envelope) {
    SoapXml.SoapReply reply = service.handle(envelope);
    return ResponseEntity.status(reply.status()).contentType(TEXT_XML_UTF8).body(reply.envelope());
  }

  @PostMapping("/sim/soapbill/fault")
  SoapBillerService.Fault force(@RequestBody SoapBillerService.Fault fault) {
    service.forceFault(fault);
    return fault;
  }

  @DeleteMapping("/sim/soapbill/fault")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void clear() {
    service.clearFault();
  }
}
