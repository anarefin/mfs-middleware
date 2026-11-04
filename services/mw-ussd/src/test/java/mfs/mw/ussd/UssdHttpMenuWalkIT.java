package mfs.mw.ussd;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The menu-walk suite over the HTTP transport: robi as configured (transport HTTP). */
@SpringBootTest(properties = {"mw.ussd.sync-budget=2s", "mw.ussd.instance-id=it-ussd-walk-http",
    "mw.ussd.consumers.enabled=true"})
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
@DirtiesContext
class UssdHttpMenuWalkIT extends UssdMenuWalkSuite {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final MockMvcTester http;

  @Autowired
  UssdHttpMenuWalkIT(MockMvcTester http) {
    this.http = http;
  }

  @Override
  protected String instanceId() {
    return "it-ussd-walk-http";
  }

  @Override
  protected Walk walk(List<String> inputs) {
    String session = "walk-" + UUID.randomUUID();
    List<String> screens = new ArrayList<>();
    JsonNode r = step(session, "BEGIN", "");
    screens.add(r.path("message").asString());
    for (String input : inputs) {
      if ("END".equals(r.path("flag").asString())) {
        break;
      }
      r = step(session, "CONTINUE", input);
      screens.add(r.path("message").asString());
    }
    return new Walk(List.copyOf(screens), "END".equals(r.path("flag").asString()));
  }

  private JsonNode step(String session, String type, String input) {
    ObjectNode body = JSON.createObjectNode();
    body.put("sessionId", session).put("msisdn", CUSTOMER).put("serviceCode", "*268#").put("type", type)
        .put("input", input);
    var result = http.post().uri("/ussd/robi").contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(body)).exchange();
    assertThat(result).hasStatusOk();
    try {
      return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }
}
