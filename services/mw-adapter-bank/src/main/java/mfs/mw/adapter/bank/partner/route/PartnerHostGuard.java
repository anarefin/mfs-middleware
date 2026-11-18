package mfs.mw.adapter.bank.partner.route;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import mfs.mw.adapter.bank.partner.config.PartnerEndpointPolicy;
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
 * resolved address is internal (unless a static dev host), and any endpoint option but the fixed
 * set {@code mw-http-call} adds ({@link #OPTIONS}); the
 * refusal ends in {@code mw-http-call}'s fallback as NOT_SENT {@code MISCONFIGURED} and is logged
 * as {@code security.partner_target_refused}. Tickets 85 and 86 add {@code cxf*} and {@code sftp*}.
 */
@Component
public class PartnerHostGuard extends RouteBuilder {

  static final String ANCHOR = "mw-partner-host-guard";

  /**
   * The only options a partner http endpoint may carry, those {@code mw-http-call} sets: name ->
   * the value it must have (null: a number of milliseconds).
   */
  static final Map<String, String> OPTIONS = new java.util.HashMap<>(Map.of(
      "throwExceptionOnFailure", "false",
      "followRedirects", "false",
      "headerFilterStrategy", "#mwPartnerHeaderFilter"));

  static {
    OPTIONS.put("connectTimeout", null);
    OPTIONS.put("responseTimeout", null);
    OPTIONS.put("soTimeout", null);
  }

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
    String plain = endpoint == null ? "" : endpoint;
    int q = plain.indexOf('?');
    try {
      uri = URI.create(q < 0 ? plain : plain.substring(0, q));
    } catch (IllegalArgumentException e) {
      return "unreadable endpoint";
    }
    if (q >= 0) {
      String option = optionProblem(plain.substring(q + 1));
      if (option != null) {
        return option;
      }
    }
    String host = uri.getHost() == null ? "" : PartnerEndpointPolicy.bare(uri.getHost());
    if (runtime.allowedHosts().stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
      return "host not in the partner's allowedHosts";
    }
    if (!"https".equalsIgnoreCase(uri.getScheme()) && !policy.insecureAllowed(host)) {
      return "plain http to a host not allowed it";
    }
    if (uri.getRawUserInfo() != null || uri.getRawQuery() != null) {
      return "user info or query on the endpoint";
    }
    return policy.internalAddressAllowed(runtime.id(), host) ? null : policy.addressProblem(host);
  }

  /** The first endpoint option outside {@link #OPTIONS} (or with another value); null when none. */
  static String optionProblem(String query) {
    for (String pair : query.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      if (!OPTIONS.containsKey(name)) {
        return "endpoint option " + name + " is not one mw-http-call sets";
      }
      String expected = OPTIONS.get(name);
      if (expected == null ? !value.matches("\\d{1,7}") : !expected.equals(value)) {
        return "endpoint option " + name + " has another value than mw-http-call sets";
      }
    }
    return null;
  }
}
