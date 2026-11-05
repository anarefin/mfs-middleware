package mfs.mw.adapter.telco.partner.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import mfs.mw.adapter.telco.partner.CredentialRef;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Defence in depth behind the config check: the resolver reads partner secrets only, never any
 * other property of the service (database password, Vault token). Values are obviously fake.
 */
class EnvironmentCredentialResolverTest {

  final MockEnvironment env =
      new MockEnvironment()
          .withProperty("mw.partner-secrets.gp.api-key", "fake-gp-key")
          .withProperty("spring.datasource.password", "fake-db-password");
  final EnvironmentCredentialResolver resolver = new EnvironmentCredentialResolver(env);

  @Test
  void resolvesAPartnerSecret() {
    assertThat(resolver.secret(new CredentialRef("mw.partner-secrets.gp.api-key"))).isEqualTo("fake-gp-key");
  }

  @Test
  void refusesAnyPropertyOutsideThePartnerSecrets() {
    assertThatThrownBy(() -> resolver.secret(new CredentialRef("spring.datasource.password")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("fake-db-password");
    assertThatThrownBy(() -> resolver.secret(new CredentialRef("mw.partner-secrets")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aMissingSecretOrNoRefIsAnError() {
    assertThatThrownBy(() -> resolver.secret(new CredentialRef("mw.partner-secrets.gp.other")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> resolver.secret(CredentialRef.NONE)).isInstanceOf(IllegalStateException.class);
  }
}
