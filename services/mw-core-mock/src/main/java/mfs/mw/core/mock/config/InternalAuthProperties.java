package mfs.mw.core.mock.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.internal-auth}: who may call the core wallet API {@code /core/v1/**} (spec 18.2, ADR
 * D31, ticket 93). A Keycloak client-credentials JWT of realm {@code services}, checked for
 * signature, expiry, exact issuer and audience, from one of the allowed clients ({@code azp}).
 *
 * @param jwkSetUri the realm's signing keys, fetched lazily on the first token
 * @param issuer exact {@code iss}
 * @param audience this service's name, required in {@code aud}
 * @param allowedClients client ids ({@code azp}) allowed to call the wallet operations
 * @param reportClients client ids ({@code azp}) allowed to read only the reports ({@code GET
 *     /core/v1/reports/emoney-total}, ticket 127c: mw-recon); nothing else of {@code /core/**}
 * @param balanceClients client ids ({@code azp}) allowed to read only balances ({@code GET
 *     /core/v1/balance/{walletId}}, ticket 129c: mw-portal-api, org wallets only); nothing else
 * @param accountClients client ids ({@code azp}) allowed to read only an account ({@code GET
 *     /core/v1/accounts/{msisdn}}, ticket 119c: mw-adapter-remit's beneficiary lookup); nothing else
 * @param kycClients client ids ({@code azp}) allowed to set only the KYC level ({@code PUT
 *     /core/v1/accounts/{walletId}/kyc-level}, ticket 124b: mw-compliance); nothing else, and no allowed
 *     client may set it
 */
@Validated
@ConfigurationProperties("mw.internal-auth")
public record InternalAuthProperties(
    @NotBlank String jwkSetUri,
    @NotBlank String issuer,
    @NotBlank String audience,
    @NotEmpty Set<String> allowedClients,
    Set<String> reportClients,
    Set<String> balanceClients,
    Set<String> accountClients,
    Set<String> kycClients) {

  public InternalAuthProperties {
    allowedClients = Set.copyOf(allowedClients == null ? Set.of() : allowedClients);
    reportClients = Set.copyOf(reportClients == null ? Set.of() : reportClients);
    balanceClients = Set.copyOf(balanceClients == null ? Set.of() : balanceClients);
    accountClients = Set.copyOf(accountClients == null ? Set.of() : accountClients);
    kycClients = Set.copyOf(kycClients == null ? Set.of() : kycClients);
  }
}
