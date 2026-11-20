package mfs.mw.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.networknt.schema.Schema;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.contracts.OpenApiSchemas;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /admin/v1/ussd/menus/preview} (ticket 133b, FRS ADM-06, CH-USSD-06): a draft tree is
 * checked by {@code MenuValidator} (refused with its problems, 400 MW-VAL-040) and then walked
 * screen by screen at the MNO's limits, with the short code the MNO dials and the root menu it
 * opens. Nothing is stored or published.
 */
@SpringBootTest(properties = "mw.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class UssdPreviewApiIT {

  static final String CONTRACT = "api/mw-admin-api/admin.v1.openapi.yaml";
  static final String PATH = "/admin/v1/ussd/menus/preview";

  private final MockMvcTester mvc;
  private final JsonMapper json;
  private final JdbcTemplate jdbc;

  @Autowired
  UssdPreviewApiIT(MockMvcTester mvc, JsonMapper json, JdbcTemplate jdbc) {
    this.mvc = mvc;
    this.json = json;
    this.jdbc = jdbc;
  }

  static RequestPostProcessor operator(String username, String... roles) {
    return jwt().jwt(j -> j.subject("sub-" + username).issuer("http://keycloak:8080/realms/ops")
        .claim("preferred_username", username).claim("realm_access", Map.of("roles", List.of(roles))));
  }

  static final RequestPostProcessor MAKER = operator("ops-maker01", "viewer", "ops-maker");
  static final RequestPostProcessor CHECKER = operator("ops-checker01", "viewer", "ops-checker");
  static final RequestPostProcessor VIEWER = operator("ops-viewer01", "viewer");

  private ObjectNode seedTree() throws IOException {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream("menus/main-v2.json")) {
      ObjectNode tree = (ObjectNode) json.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      tree.remove("menuId");
      tree.remove("version");
      return tree;
    }
  }

  private String body(ObjectNode tree, String mno, String language, List<String> inputs) {
    ObjectNode b = json.createObjectNode();
    b.set("menu", tree);
    b.put("menuId", "main");
    b.put("mno", mno);
    b.put("language", language);
    ArrayNode in = b.putArray("inputs");
    inputs.forEach(in::add);
    return json.writeValueAsString(b);
  }

  private MvcTestResult preview(RequestPostProcessor who, String body) {
    return mvc.post().uri(PATH).with(who).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  private JsonNode read(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }

  private static void assertResponse(String status, JsonNode body) {
    Schema schema = OpenApiSchemas.response(CONTRACT, PATH, "post", status);
    assertThat(schema.validate(body)).as("%s", body).isEmpty();
  }

  @Test
  void aLongBanglaMenuIsPagedAtTheUcs2LimitWithBackAndTheShortCodeIsNamed() throws IOException {
    ObjectNode tree = seedTree();
    ObjectNode main = (ObjectNode) tree.path("nodes").path("main");
    main.putObject("text").put("bn", "আপনার সেবা বেছে নিন, বিটস মোবাইল আর্থিক সেবায় স্বাগতম").put("en", "Bits");
    long before = jdbc.queryForObject("select count(*) from change_request", Long.class);

    MvcTestResult r = preview(MAKER, body(tree, "robi", "bn", List.of("0", "00")));

    assertThat(r).hasStatusOk();
    JsonNode b = read(r);
    assertResponse("200", b);
    assertThat(b.path("shortCode").asString()).isEqualTo("*269#");
    assertThat(b.path("rootMenuId").asString()).isEqualTo("main");
    JsonNode dial = b.path("screens").get(0);
    assertThat(dial.path("pages").asInt()).isGreaterThan(1);
    assertThat(dial.path("screen").asString()).hasSizeLessThanOrEqualTo(70).endsWith("\n00. পেছনে");
    assertThat(b.path("screens").get(1).path("page").asInt()).isEqualTo(1);
    assertThat(b.path("screens").get(2).path("page").asInt()).isZero();
    assertThat(jdbc.queryForObject("select count(*) from change_request", Long.class)).as("nothing stored").isEqualTo(before);
  }

  @Test
  void aWalkMasksThePinAnswer() throws IOException {
    MvcTestResult r = preview(CHECKER, body(seedTree(), "gp", "en", List.of("1", "01712000002", "100", "1", "12345")));

    assertThat(r).hasStatusOk();
    JsonNode screens = read(r).path("screens");
    assertThat(screens.get(screens.size() - 1).path("input").asString()).isEqualTo("****");
    assertThat(screens.get(screens.size() - 1).path("ended").asBoolean()).isTrue();
    assertThat(r.getResponse().getContentAsString()).doesNotContain("12345");
  }

  @Test
  void anInvalidTreeIsRefusedWithTheValidatorProblems() throws IOException {
    ObjectNode tree = seedTree();
    ObjectNode main = (ObjectNode) tree.path("nodes").path("main");
    ((ObjectNode) main.path("options").get(0)).put("next", "nowhere");
    ((ObjectNode) tree.path("nodes").path("p2p.pin")).put("store", "pin");

    MvcTestResult r = preview(MAKER, body(tree, "gp", "bn", List.of()));

    assertThat(r).hasStatus(400);
    JsonNode e = read(r);
    assertThat(OpenApiSchemas.component(CONTRACT, "ErrorEnvelope").validate(e)).as("%s", e).isEmpty();
    assertThat(e.path("code").asString()).isEqualTo("MW-VAL-040");
    assertThat(e.path("details").findValuesAsString("issue"))
        .contains("main: option 1 points to missing node nowhere", "p2p.pin: a PIN input is never stored");
  }

  @Test
  void anUnknownMnoIsRefused() throws IOException {
    MvcTestResult r = preview(MAKER, body(seedTree(), "nope", "bn", List.of()));

    assertThat(r).hasStatus(400);
    assertThat(read(r).path("code").asString()).isEqualTo("MW-VAL-040");
  }

  @Test
  void onlyContentMakersAndCheckersMayPreview() throws IOException {
    assertThat(preview(VIEWER, body(seedTree(), "gp", "bn", List.of()))).hasStatus(403);
    assertThat(preview(operator("ops-admin01", "integration-maker"), body(seedTree(), "gp", "bn", List.of())))
        .hasStatus(403);
  }
}
