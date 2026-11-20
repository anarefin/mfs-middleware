package mfs.mw.adapter.remit.partner.auth;

import org.apache.camel.component.hashicorp.vault.HashicorpVaultPropertiesFunction;
import org.springframework.vault.core.VaultTemplate;

/**
 * Camel's {@code hashicorp} properties function on Spring Cloud Vault's {@link VaultTemplate}
 * (AppRole login, token renewal) instead of a static token (ADR D28). Camel 4.22's {@code doStart}
 * rebuilds its client from {@code camel.vault.hashicorp.token/host/port/scheme} even when one was
 * passed in, and fails without them; this keeps the template given here. Reading ({@code apply})
 * is Camel's own: KV v2 {@code <engine>/data/<path>}, field after {@code #}. No refresh (D28).
 */
public final class SpringVaultPropertiesFunction extends HashicorpVaultPropertiesFunction {

  public SpringVaultPropertiesFunction(VaultTemplate template) {
    super(template);
  }

  @Override
  protected void doStart() {
    // the client is the VaultTemplate given to the constructor
  }
}
