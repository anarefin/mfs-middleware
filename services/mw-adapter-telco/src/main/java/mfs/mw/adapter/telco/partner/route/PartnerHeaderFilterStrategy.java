package mfs.mw.adapter.telco.partner.route;

import java.util.Set;
import org.apache.camel.support.DefaultHeaderFilterStrategy;

/**
 * The headers of a partner HTTP call ({@code mw-http-call}, bean {@code mwPartnerHeaderFilter}):
 * message headers are sent as HTTP headers, so this keeps every {@code mw*} header (call
 * reference, correlation and partner ids, ...) and every {@code Camel*} control header in the
 * service, as Camel's HTTP filter does for {@code Camel*} and the hop-by-hop headers; and a
 * partner's response can set none of them on our exchange.
 */
public final class PartnerHeaderFilterStrategy extends DefaultHeaderFilterStrategy {

  static final String INTERNAL = "(?i)^(camel|org\\.apache\\.camel|mw).*";

  public PartnerHeaderFilterStrategy() {
    setLowerCase(true);
    setOutFilter(Set.of(
        "content-length", "host", "cache-control", "connection", "date", "pragma", "trailer",
        "transfer-encoding", "upgrade", "via", "warning"));
    setOutFilterPattern(INTERNAL);
    setInFilterPattern(INTERNAL);
  }
}
