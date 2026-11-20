package mfs.mw.audit.dw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The audit query features of ticket 130 under {@code mw.audit-dw.query}.
 *
 * @param tokeniseMsisdn whether the search projection tokenises MSISDNs at mw-crypto ({@code
 *     TOKENISE_MSISDN}, needs the anchor's mw-crypto client); off, no MSISDN token is stored and an
 *     MSISDN search answers 503 {@code MW-SYS-002}
 * @param exportMaxRows the most rows one CSV export returns (FRS AUD-05); more matches are cut and
 *     the export says so in its {@code X-Mw-Export-Truncated} header
 */
@ConfigurationProperties("mw.audit-dw.query")
public record AuditQueryProperties(
    @DefaultValue("true") boolean tokeniseMsisdn, @DefaultValue("10000") int exportMaxRows) {}
