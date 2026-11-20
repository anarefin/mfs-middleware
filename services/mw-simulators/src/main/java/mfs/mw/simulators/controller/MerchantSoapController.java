package mfs.mw.simulators.controller;

import mfs.mw.simulators.service.MerchantSoapMock;
import mfs.mw.simulators.util.SoapXml;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The merchant system SOAP endpoint (ticket 115b): {@code POST /merchant-soap/v1/soap} (SOAP 1.1,
 * {@code text/xml}), ValidateMerchant, NotifyPayment, QueryNotify and Refund. See {@link MerchantSoapMock}.
 */
@RestController
public class MerchantSoapController {

  private static final MediaType TEXT_XML_UTF8 = MediaType.parseMediaType("text/xml;charset=UTF-8");

  private final MerchantSoapMock mock;

  public MerchantSoapController(MerchantSoapMock mock) {
    this.mock = mock;
  }

  @PostMapping(value = "/merchant-soap/v1/soap", consumes = {MediaType.TEXT_XML_VALUE, "application/soap+xml"})
  ResponseEntity<String> soap(@RequestBody(required = false) String envelope) {
    SoapXml.SoapReply reply = mock.handle(envelope);
    return ResponseEntity.status(reply.status()).contentType(TEXT_XML_UTF8).body(reply.envelope());
  }
}
