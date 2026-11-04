package mfs.mw.adapter.bank.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import mfs.mw.adapter.bank.dto.CallbackAck;
import mfs.mw.adapter.bank.mapper.InboundCallMapper;
import mfs.mw.adapter.bank.service.PartnerCallbackService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partner callbacks (ticket 22): {@code POST /partner/{partnerId}/callback/**}, reached from
 * outside only through the APISIX route of that partner (ip-restriction, rate limit, body cap;
 * the signature is verified here, with the partner's auth scheme and secret). The partner id must
 * be an instance id ({@code @Pattern}; else 400 {@code MW-VAL-001}); {@link InboundCallMapper}
 * passes the raw body and the path as received on unchanged. 200 {@link CallbackAck} only once
 * the event is on Kafka.
 */
@RestController
public class PartnerCallbackController {

  /** A partner instance id (adapter framework rule). */
  static final String PARTNER_ID = "^[a-z0-9][a-z0-9-]{0,63}$";

  private final PartnerCallbackService service;
  private final InboundCallMapper mapper;

  public PartnerCallbackController(PartnerCallbackService service, InboundCallMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @PostMapping("/partner/{partnerId}/callback/**")
  CallbackAck callback(
      @PathVariable @Pattern(regexp = PARTNER_ID) String partnerId,
      @RequestBody(required = false) byte[] body,
      HttpServletRequest request) {
    return service.receive(mapper.toInboundCall(partnerId, request, body));
  }
}
