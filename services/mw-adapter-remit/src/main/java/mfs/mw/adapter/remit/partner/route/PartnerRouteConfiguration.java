package mfs.mw.adapter.remit.partner.route;

import org.apache.camel.builder.RouteConfigurationBuilder;
import org.springframework.stereotype.Component;

/**
 * The one error handling of every partner route (ADR D28), owned by the loader, which sets it on
 * each route it loads ({@code routeConfigurationId}); a partner route cannot declare its own. No
 * redelivery, ever: a PAY is not idempotent, and an INQUIRY / STATUS_QUERY goes again only inside
 * {@code mw-http-call}, chosen by the operation, never by the route. Any exception (a mapping that
 * fails, a broken bean) ends the call handled, as NOT_SENT {@code MISCONFIGURED} when nothing was
 * sent yet, else UNKNOWN, and is audited ({@code mwAudit.failed}). {@code defaultErrorHandler}
 * with no redeliveries, not {@code noErrorHandler}: the latter ignores {@code onException}.
 */
@Component
public class PartnerRouteConfiguration extends RouteConfigurationBuilder {

  public static final String ID = "mw-partner";

  @Override
  public void configuration() {
    routeConfiguration(ID)
        .errorHandler(defaultErrorHandler().maximumRedeliveries(0).logStackTrace(false).logExhausted(false))
        .onException(Exception.class)
        .handled(true)
        .maximumRedeliveries(0)
        .logStackTrace(false)
        .logExhausted(false)
        .to("bean:mwAudit?method=failed");
  }
}
