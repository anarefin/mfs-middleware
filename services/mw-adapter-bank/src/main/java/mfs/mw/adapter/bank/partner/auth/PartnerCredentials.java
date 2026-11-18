package mfs.mw.adapter.bank.partner.auth;

import mfs.mw.adapter.bank.partner.CredentialRef;
import org.apache.camel.CamelContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Partner credentials through Camel's HashiCorp Vault properties function pointed at OpenBao (ADR
 * D28): field {@code <field>} of partner {@code <id>} is {@code
 * {{hashicorp:secret:<service>/<id>#<field>}}}, i.e. OpenBao KV v2 {@code secret/<service>/<id>}.
 * The path is built here from the partner's own id, so a record can name only a field of its own
 * secret, never another partner's or the service's. Read when a partner route loads and kept with
 * that version (no refresh: D28, static secrets). A value is never logged; a failure names the
 * partner and field only. Replaces the former {@code EnvironmentCredentialResolver}.
 */
@Component
public class PartnerCredentials {

  private final CamelContext camel;
  private final String service;

  public PartnerCredentials(CamelContext camel, @Value("${spring.application.name}") String service) {
    this.camel = camel;
    this.service = service;
  }

  /**
   * @throws IllegalStateException when the field name is not a plain name or no value is stored
   */
  public String read(String partnerId, String field) {
    if (field == null || !CredentialRef.NAME.matcher(field).matches()) {
      throw new IllegalStateException("credential field of " + partnerId + " must be lower-case letters, digits, hyphen");
    }
    String value;
    try {
      value = camel.resolvePropertyPlaceholders("{{hashicorp:secret:" + service + "/" + partnerId + "#" + field + "}}");
    } catch (RuntimeException e) {
      throw new IllegalStateException("no credential " + field + " for partner " + partnerId + " (" + e.getClass().getSimpleName() + ")");
    }
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException("no credential " + field + " for partner " + partnerId);
    }
    return value;
  }
}
