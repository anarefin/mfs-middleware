package mfs.mw.adapter.interop.controller;

import java.nio.charset.StandardCharsets;
import mfs.mw.adapter.interop.exception.ErrorCodes;
import mfs.mw.adapter.interop.exception.MwException;
import mfs.mw.adapter.interop.service.NpsbInboundService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The NPSB switch's inbound credit (ticket 121b part 2): {@code POST /partner/v1/npsb/inbound} with a
 * signed ISO 20022 pacs.008 as the raw body (application/xml), answered 200 with our signed pacs.002
 * ({@code ACSP} accepted, also for a re-send; {@code RJCT} refused). Reached only through APISIX (route
 * {@code partner-npsb-inbound}: client certificate where the edge has one, HMAC and an IP allow-list);
 * the signature is verified in the service. The raw body is passed on as received, because that is
 * what was signed.
 */
@RestController
public class NpsbInboundController {

  private final NpsbInboundService service;

  public NpsbInboundController(NpsbInboundService service) {
    this.service = service;
  }

  @PostMapping(
      path = "/partner/v1/npsb/inbound",
      consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
  ResponseEntity<String> inbound(@RequestBody(required = false) byte[] body) {
    if (body == null || body.length == 0) {
      throw new MwException(ErrorCodes.VAL_MALFORMED, "The request body is empty").withStatus(HttpStatus.BAD_REQUEST);
    }
    NpsbInboundService.Answer answer = service.receive(new String(body, StandardCharsets.UTF_8));
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(answer.pacs002());
  }
}
