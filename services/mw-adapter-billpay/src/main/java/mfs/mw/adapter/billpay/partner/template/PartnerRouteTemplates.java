package mfs.mw.adapter.billpay.partner.template;

import mfs.mw.adapter.billpay.partner.InboundCall;
import mfs.mw.adapter.billpay.partner.PartnerCall;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * The Camel route templates (spec 12.1 layer 1), written in the Java DSL. A partner instance is
 * one route created from a template by {@link PartnerRouteRegistrar} through {@code
 * TemplatedRouteBuilder}, with the single parameter {@code partner} (instance id): {@code
 * direct:partner-<id>} takes a {@link PartnerCall} (reply: a {@code PartnerResponse}) or, for
 * {@code rest-json-async-callback}, also an {@link InboundCall} (reply: an {@code
 * InboundResult}). Both run the {@link PartnerCallWrapper}; what differs by template is the
 * adapter behaviour it selects ({@link RestJsonAdapter}: PENDING and callbacks only for the async
 * template). Everything else (URL, auth, timeouts, mappings, error map) is read from the config
 * snapshot at call time, so only a template change re-creates the route.
 */
public class PartnerRouteTemplates extends RouteBuilder {

  /** Header carrying the partner instance id inside a templated route. */
  public static final String INSTANCE_HEADER = "mwPartnerInstance";

  public static final String PARAM = "partner";

  private final PartnerCallWrapper wrapper;

  public PartnerRouteTemplates(PartnerCallWrapper wrapper) {
    this.wrapper = wrapper;
  }

  @Override
  public void configure() {
    // Same route shape for both; the adapter of a request-reply partner refuses callbacks.
    for (String template : new String[] {PartnerTemplates.REQUEST_REPLY, PartnerTemplates.ASYNC_CALLBACK}) {
      routeTemplate(template)
          .templateParameter(PARAM, null, "partner instance id")
          .from("direct:partner-{{" + PARAM + "}}")
          .setHeader(INSTANCE_HEADER, constant("{{" + PARAM + "}}"))
          .choice()
          .when(body().isInstanceOf(InboundCall.class))
          .process(this::inbound)
          .otherwise()
          .process(this::outbound)
          .end();
    }
  }

  private void outbound(Exchange exchange) throws Exception {
    String instance = exchange.getMessage().getHeader(INSTANCE_HEADER, String.class);
    PartnerCall call = exchange.getMessage().getMandatoryBody(PartnerCall.class);
    exchange.getMessage().setBody(wrapper.call(instance, call));
  }

  private void inbound(Exchange exchange) throws Exception {
    String instance = exchange.getMessage().getHeader(INSTANCE_HEADER, String.class);
    InboundCall call = exchange.getMessage().getMandatoryBody(InboundCall.class);
    exchange.getMessage().setBody(wrapper.inbound(instance, call));
  }
}
