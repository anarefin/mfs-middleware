package mfs.mw.adapter.billpay.partner.route;

import java.net.URI;
import java.util.Locale;
import mfs.mw.adapter.billpay.partner.config.PartnerEndpointPolicy;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The host allowlist before every send (ADR D26, guardrail 4): one {@code
 * interceptSendToEndpoint("http*")} (also {@code https:}) for the whole context, declared once at
 * start-up on an anchor route ({@value #ANCHOR}). Camel registers it as a context-wide endpoint
 * callback, so it covers every partner route version and Kamelet route; declared per partner route
 * it would pile up one callback per load. It refuses, before any network I/O, a send whose resolved
 * host is not in the calling partner's {@code allowedHosts} (from the call's runtime, not a header),
 * one over plain http to a host not allowed it, and any {@code CamelHttpUri} override; the
 * refusal ends in {@code mw-http-call}'s fallback as NOT_SENT {@code MISCONFIGURED} and is logged
 * as {@code security.partner_target_refused}. Tickets 85 and 86 add {@code cxf*} and {@code sftp*}.
 */
@Component
public class PartnerHostGuard extends RouteBuilder {

  static final String ANCHOR = "mw-partner-host-guard";

  private static final Logger log = LoggerFactory.getLogger(PartnerHostGuard.class);

  private final PartnerEndpointPolicy policy;

  public PartnerHostGuard(PartnerEndpointPolicy policy) {
    this.policy = policy;
  }

  @Override
  public void configure() {
    interceptSendToEndpoint("http*").process(this::check);
    from("direct:" + ANCHOR).routeId(ANCHOR).log("host guard anchor");
  }

  void check(Exchange exchange) {
    PartnerRuntime runtime = exchange.getProperty(PartnerExchange.RUNTIME, PartnerRuntime.class);
    String endpoint = exchange.getProperty(Exchange.INTERCEPTED_ENDPOINT, String.class);
    String problem = problem(runtime, endpoint, exchange.getMessage().getHeader(Exchange.HTTP_URI, String.class));
    if (problem != null) {
      log.atWarn()
          .addKeyValue("event", "security.partner_target_refused")
          .addKeyValue("partnerId", runtime == null ? null : runtime.id())
          .addKeyValue("detail", problem)
          .log("partner request would leave the approved hosts; not sent");
      throw new PartnerHostRefusedException(problem);
    }
  }

  String problem(PartnerRuntime runtime, String endpoint, String httpUriHeader) {
    if (runtime == null) {
      return "an http call outside a partner call";
    }
    if (httpUriHeader != null) {
      return "CamelHttpUri may not redirect a partner call";
    }
    URI uri;
    try {
      String plain = endpoint == null ? "" : endpoint;
      int q = plain.indexOf('?');
      uri = URI.create(q < 0 ? plain : plain.substring(0, q));
    } catch (IllegalArgumentException e) {
      return "unreadable endpoint";
    }
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (runtime.allowedHosts().stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
      return "host not in the partner's allowedHosts";
    }
    if (!"https".equalsIgnoreCase(uri.getScheme()) && !policy.insecureAllowed(host)) {
      return "plain http to a host not allowed it";
    }
    return null;
  }
}
