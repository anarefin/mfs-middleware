package mfs.mw.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.auth.config.DeviceBindingConfig;
import mfs.mw.auth.config.PublicConnectorConfig;
import mfs.mw.auth.config.SecurityConfig;
import mfs.mw.auth.service.JwksService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Without {@code mw.auth.seed-api.enabled} (the default) the account test API does not exist: 404 envelope. */
@WebMvcTest(controllers = TestAccountController.class)
@Import({SecurityConfig.class, PublicConnectorConfig.class, DeviceBindingConfig.class})
class TestAccountApiOffTest {

  private final MockMvcTester mvc;

  @MockitoBean JwksService jwks;

  @Autowired
  TestAccountApiOffTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @Test
  void theTestPathsAreNotThere() {
    for (var r : java.util.List.of(mvc.get().uri("/test/v1/accounts/01711000001").exchange(),
        mvc.post().uri("/test/v1/accounts/01711000001/unlock").exchange(),
        mvc.post().uri("/test/v1/accounts/01711000001/unbind").exchange())) {
      assertThat(r).hasStatus(404);
      assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-003");
    }
  }
}
