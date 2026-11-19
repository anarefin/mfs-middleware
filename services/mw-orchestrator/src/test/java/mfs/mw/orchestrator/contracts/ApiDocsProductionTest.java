package mfs.mw.orchestrator.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Without the {@code dev} or {@code test} profile (production) the service publishes no API
 * document and no Swagger UI (ticket 101, PLT-052): {@code springdoc.api-docs.enabled} and
 * {@code springdoc.swagger-ui.enabled} are false in {@code application.yml}.
 */
@WebMvcTest(properties = {"spring.test.mockmvc.print=none"})
@AutoConfigureMockMvc(addFilters = false)
@Import(MockedWebSlice.class)
class ApiDocsProductionTest {

  private final MockMvcTester mvc;

  @Autowired
  ApiDocsProductionTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @Test
  void noApiDocumentOutsideDevAndTest() {
    assertThat(mvc.get().uri("/v3/api-docs")).hasStatus(404);
  }

  @Test
  void noSwaggerUiOutsideDevAndTest() {
    assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatus(404);
    assertThat(mvc.get().uri("/swagger-ui.html")).hasStatus(404);
  }
}
