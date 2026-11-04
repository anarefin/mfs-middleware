package mfs.mw.adapter.telco.partner.auth;

import mfs.mw.adapter.telco.partner.CredentialRef;
import mfs.mw.adapter.telco.partner.CredentialResolver;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Partner secrets from the Spring environment, which Spring Cloud Vault fills from OpenBao
 * ({@code secret/<service>}, AppRole) when {@code VAULT_ENABLED=true} (spec 18.4): a credentials
 * ref {@code partners.gp.api-key} is the property of that name. Looked up at call time, so a
 * refreshed property source is seen by the next call; the value is never logged.
 */
@Component
public class EnvironmentCredentialResolver implements CredentialResolver {

  private final Environment environment;

  public EnvironmentCredentialResolver(Environment environment) {
    this.environment = environment;
  }

  @Override
  public String secret(CredentialRef ref) {
    if (ref == null || ref.isNone()) {
      throw new IllegalStateException("no credentials reference configured");
    }
    String value = environment.getProperty(ref.path());
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException("no secret stored under " + ref.path());
    }
    return value;
  }
}
