package mfs.mw.adapter.telco.partner;

import java.net.http.HttpClient;
import mfs.mw.adapter.telco.partner.config.PartnerConfig;

/**
 * What the framework hands an adapter for one call (spec 12.2): the partner's config snapshot at
 * call time, its auth scheme and credentials reference, an HTTP client built for its timeouts,
 * and the {@link ExchangeRecord} the adapter fills with the raw request and response so the
 * wrapper can audit them masked. Adapters never touch Kafka or the database.
 *
 * @param instance partner instance id (config key without {@code partner/})
 */
public record PartnerContext(
    String instance,
    PartnerConfig config,
    AuthScheme auth,
    CredentialRef credentials,
    HttpClient http,
    ExchangeRecord exchange) {}
