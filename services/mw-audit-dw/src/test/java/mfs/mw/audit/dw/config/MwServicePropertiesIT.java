package mfs.mw.audit.dw.config;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.audit.dw.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Static config binds without Vault (VAULT_ENABLED defaults to false for local runs). */
@SpringBootTest(properties = "mw.service.env=sit")
@Import(TestInfrastructure.class)
class MwServicePropertiesIT {

  private final MwServiceProperties props;

  @Autowired
  MwServicePropertiesIT(MwServiceProperties props) {
    this.props = props;
  }

  @Test
  void bindsServiceIdentity() {
    assertThat(props.name()).isEqualTo("mw-audit-dw");
    assertThat(props.env()).isEqualTo("sit");
    assertThat(props.version()).isNotBlank();
  }
}
