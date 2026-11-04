package mfs.mw.adapter.bank.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mfs.mw.adapter.bank.dto.CallbackAck;
import mfs.mw.adapter.bank.exception.ErrorCodes;
import mfs.mw.adapter.bank.exception.MwException;
import mfs.mw.adapter.bank.partner.InboundCall;
import mfs.mw.adapter.bank.service.PartnerCallbackService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partner callbacks (ticket 22): {@code POST /partner/{partnerId}/callback/**}, reached from
 * outside only through the APISIX route of that partner (ip-restriction, rate limit, body cap;
 * the signature is verified here, with the partner's auth scheme and secret). The raw body and
 * the path as received are what the partner signed, so both are passed on unchanged. 200 {@link
 * CallbackAck} only once the event is on Kafka.
 */
@RestController
public class PartnerCallbackController {

  private static final java.util.regex.Pattern PARTNER_ID = java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

  private final PartnerCallbackService service;

  public PartnerCallbackController(PartnerCallbackService service) {
    this.service = service;
  }

  @PostMapping("/partner/{partnerId}/callback/**")
  CallbackAck callback(
      @PathVariable String partnerId, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
    if (!PARTNER_ID.matcher(partnerId).matches()) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "No such callback target").withStatus(HttpStatus.NOT_FOUND);
    }
    Map<String, String> headers = new LinkedHashMap<>();
    for (String name : Collections.list(request.getHeaderNames())) {
      headers.put(name, request.getHeader(name));
    }
    String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
    return service.receive(new InboundCall(partnerId, request.getMethod(), path, headers, body));
  }
}
