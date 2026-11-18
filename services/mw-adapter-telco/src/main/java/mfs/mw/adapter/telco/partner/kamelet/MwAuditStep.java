package mfs.mw.adapter.telco.partner.kamelet;

import mfs.mw.adapter.telco.partner.PartnerResponse;
import mfs.mw.adapter.telco.partner.PartnerResponse.NotSentReason;
import mfs.mw.adapter.telco.partner.PartnerResponse.UnknownReason;
import mfs.mw.adapter.telco.partner.route.PartnerAudit;
import mfs.mw.adapter.telco.partner.route.PartnerExchange;
import mfs.mw.adapter.telco.partner.route.PartnerResult;
import mfs.mw.adapter.telco.partner.route.PartnerRuntime;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The step of the {@code mw-audit} Kamelet (bean {@code mwAudit}), the last step of every partner
 * route: the masked audit of the call (spec 12.2, 17) through {@link PartnerAudit} and {@code
 * messaging/PartnerAuditPublisher}, with the record's {@code sensitiveFields}. Headers are never
 * audited (they carry credentials). {@link #failed} is the partner route configuration's answer to
 * an exception: NOT_SENT {@code MISCONFIGURED} when nothing was sent yet, else UNKNOWN; audited too.
 */
@Component("mwAudit")
public class MwAuditStep {

  private static final Logger log = LoggerFactory.getLogger(MwAuditStep.class);

  private final PartnerAudit audit;
  private final JsonMapper json;

  public MwAuditStep(PartnerAudit audit, JsonMapper json) {
    this.audit = audit;
    this.json = json;
  }

  public void record(Exchange exchange) {
    PartnerRuntime runtime = PartnerExchange.runtime(exchange);
    var message = exchange.getMessage();
    long started = exchange.getProperty(PartnerExchange.STARTED, System.nanoTime(), Long.class);
    boolean sent = PartnerExchange.flag(exchange, PartnerExchange.SENT);
    audit.record(
        message.getHeader(PartnerExchange.PARTNER_ID, String.class),
        runtime.id(),
        PartnerExchange.operation(exchange),
        message.getHeader(PartnerExchange.TXN_ID, String.class),
        message.getHeader(PartnerExchange.CORRELATION_ID, String.class),
        sent ? exchange.getProperty(PartnerExchange.HTTP_METHOD, String.class) : null,
        sent ? exchange.getProperty(PartnerExchange.HTTP_PATH_AND_QUERY, String.class) : null,
        exchange.getProperty(PartnerExchange.RESPONSE_STATUS, Integer.class),
        response(exchange, message.getBody(String.class)).status(),
        (System.nanoTime() - started) / 1_000_000,
        sent ? exchange.getProperty(PartnerExchange.REQUEST_BODY, String.class) : null,
        exchange.getProperty(PartnerExchange.RESPONSE_BODY, String.class),
        runtime.config().sensitiveFields());
  }

  /** The route failed with an exception (mapping, bean, validation): decide the outcome, audit it. */
  public void failed(Exchange exchange) {
    Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
    boolean sent = PartnerExchange.flag(exchange, PartnerExchange.SENT);
    log.atWarn()
        .addKeyValue("event", sent ? "partner.call.failed" : "partner.call.not_sent")
        .addKeyValue("partnerId", exchange.getMessage().getHeader(PartnerExchange.PARTNER_ID, String.class))
        .addKeyValue("errorClass", cause == null ? null : cause.getClass().getName())
        .log(sent ? "partner route failed after sending" : "partner route failed before sending");
    PartnerResult result =
        sent ? PartnerResult.unknown(UnknownReason.MALFORMED_REPLY) : PartnerResult.notSent(NotSentReason.MISCONFIGURED);
    exchange.getMessage().setBody(json.writeValueAsString(result));
    if (exchange.getProperty(PartnerExchange.RUNTIME) != null) {
      record(exchange);
    }
  }

  /**
   * The route's answer as a canonical response; a missing or unreadable body is NOT_SENT {@code
   * MISCONFIGURED} when nothing was sent (no branch of the route matched), else UNKNOWN {@code
   * MALFORMED_REPLY}.
   */
  public PartnerResponse response(Exchange exchange, String body) {
    boolean sent = PartnerExchange.flag(exchange, PartnerExchange.SENT);
    if (body != null && body.startsWith("{")) {
      try {
        PartnerResult result = json.readValue(body, PartnerResult.class);
        if (result.outcome() != null) {
          return result.toResponse();
        }
      } catch (JacksonException e) {
        // fall through: not a result
      }
    }
    return sent
        ? new PartnerResponse.Unknown(UnknownReason.MALFORMED_REPLY)
        : new PartnerResponse.NotSent(NotSentReason.MISCONFIGURED);
  }
}
