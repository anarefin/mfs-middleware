package mfs.mw.simulators.controller;

import java.util.Map;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.EcNidService;
import mfs.mw.simulators.util.SoapXml;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The EC NID mock (ticket 124b): REST verify and the SOAP front end. See {@link EcNidService}. */
@RestController
public class EcNidController {

  private static final MediaType TEXT_XML_UTF8 = MediaType.parseMediaType("text/xml;charset=UTF-8");

  private final EcNidService service;

  public EcNidController(EcNidService service) {
    this.service = service;
  }

  @PostMapping(value = "/ec/v1/nid/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Object> verify(
      @RequestBody Map<String, Object> body, @RequestHeader(value = "Authorization", required = false) String auth) {
    SimReply reply = service.verify(text(body.get("nid")), text(body.get("dob")), basic(auth));
    return ResponseEntity.status(reply.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
  }

  @PostMapping(value = "/ec/v1/soap", consumes = {MediaType.TEXT_XML_VALUE, "application/soap+xml"})
  ResponseEntity<String> soap(
      @RequestBody(required = false) String envelope,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    SoapXml.SoapReply reply = service.soap(envelope, basic(auth));
    return ResponseEntity.status(reply.status()).contentType(TEXT_XML_UTF8).body(reply.envelope());
  }

  private static boolean basic(String auth) {
    return auth != null && auth.startsWith("Basic ") && auth.length() > "Basic ".length();
  }

  private static String text(Object o) {
    return o == null ? null : o.toString();
  }
}
