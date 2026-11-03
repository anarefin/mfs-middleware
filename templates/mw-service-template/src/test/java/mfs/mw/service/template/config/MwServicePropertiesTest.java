package mfs.mw.service.template.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Static config binds without Vault (VAULT_ENABLED defaults to false for local runs). */
@SpringBootTest(properties = "mw.service.env=sit")
class MwServicePropertiesTest {

  @Autowired MwServiceProperties props;

  @Test
  void bindsServiceIdentity() {
    assertThat(props.name()).isEqualTo("mw-service-template");
    assertThat(props.env()).isEqualTo("sit");
    assertThat(props.version()).isNotBlank();
  }
}
