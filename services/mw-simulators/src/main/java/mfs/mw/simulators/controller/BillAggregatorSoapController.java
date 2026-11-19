package mfs.mw.simulators.controller;

import mfs.mw.simulators.service.BillAggregatorSoapService;
import mfs.mw.simulators.util.SoapXml;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The billagg SOAP front end: {@code POST /billagg/v1/soap} (SOAP 1.1, {@code text/xml}), FetchBill and
 * PayBill for the SOAP billers. See {@link BillAggregatorSoapService}.
 */
@RestController
public class BillAggregatorSoapController {

  private static final MediaType TEXT_XML_UTF8 = MediaType.parseMediaType("text/xml;charset=UTF-8");

  private final BillAggregatorSoapService service;

  public BillAggregatorSoapController(BillAggregatorSoapService service) {
    this.service = service;
  }

  @PostMapping(value = "/billagg/v1/soap", consumes = {MediaType.TEXT_XML_VALUE, "application/soap+xml"})
  ResponseEntity<String> soap(@RequestBody(required = false) String envelope) {
    SoapXml.SoapReply reply = service.handle(envelope);
    return ResponseEntity.status(reply.status()).contentType(TEXT_XML_UTF8).body(reply.envelope());
  }
}
