package mfs.mw.adapter.merchant.partner.route;

import java.util.Set;
import org.apache.camel.support.DefaultHeaderFilterStrategy;

/**
 * The headers of a partner SOAP call ({@code mw-soap-call}, bean {@code mwSoapHeaderFilter},
 * ticket 85): camel-cxf sends message headers as HTTP headers, so this keeps every {@code mw*}
 * header and every {@code Camel*} control header in the service as {@link
 * PartnerHeaderFilterStrategy} does, and also camel-cxf's own {@code RequestContext} / {@code
 * ResponseContext} maps and {@code org.apache.cxf.*} headers (verified: without it the request
 * context goes out as an HTTP header). The {@code SOAPAction} is set by CXF itself from the
 * Kamelet's property. A partner's response can set none of them on our exchange.
 */
public final class PartnerSoapHeaderFilterStrategy extends DefaultHeaderFilterStrategy {

  static final String INTERNAL = "(?i)^(camel|org\\.apache\\.camel|mw|requestcontext|responsecontext|org\\.apache\\.cxf).*";

  public PartnerSoapHeaderFilterStrategy() {
    setLowerCase(true);
    setOutFilter(Set.of(
        "content-length", "host", "cache-control", "connection", "date", "pragma", "trailer",
        "transfer-encoding", "upgrade", "via", "warning"));
    setOutFilterPattern(INTERNAL);
    setInFilterPattern(INTERNAL);
  }
}
