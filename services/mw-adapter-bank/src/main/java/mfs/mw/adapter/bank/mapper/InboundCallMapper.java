package mfs.mw.adapter.bank.mapper;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mfs.mw.adapter.bank.partner.InboundCall;
import org.springframework.stereotype.Component;

/**
 * A partner callback's HTTP request as the framework's {@link InboundCall} (ticket 22): the raw
 * body and the path with its query exactly as received, because that is what the partner signed,
 * and every header (the auth scheme reads its signature headers from them).
 */
@Component
public class InboundCallMapper {

  public InboundCall toInboundCall(String partnerId, HttpServletRequest request, byte[] body) {
    Map<String, String> headers = new LinkedHashMap<>();
    for (String name : Collections.list(request.getHeaderNames())) {
      headers.put(name, request.getHeader(name));
    }
    String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
    return new InboundCall(partnerId, request.getMethod(), path, headers, body);
  }
}
