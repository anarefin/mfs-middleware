package mfs.mw.recon.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mw.recon.core} (ticket 127c): the core wallet API the trust position job reads the e-money total from, and
 * this service's realm {@code services} client (mw-recon, audience mw-core-mock) that gets the token. The secret is
 * empty here and comes from Vault ({@code mw.recon.core.client-secret}, provisioned from {@code KC_MW_RECON_SECRET}).
 */
@ConfigurationProperties("mw.recon.core")
public record CoreClientProperties(
    URI baseUrl, URI tokenUri, String clientId, String clientSecret, Duration connectTimeout, Duration timeout) {}
