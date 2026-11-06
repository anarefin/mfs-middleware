package mfs.mw.ussd.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.ussd.TestInfrastructure;
import mfs.mw.ussd.dto.ConfigChangedEvent;
import mfs.mw.ussd.mapper.MenuParser;
import mfs.mw.ussd.repository.MenuRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A USSD menu version published by the admin console (ticket 39, spec 9.2, acceptance criterion
 * 1): once its go-live has passed it is what the next session sees, while a session that started
 * before finishes on its own version. Played over the HTTP transport on a test-only MNO {@code
 * itmenu}, so the shared database's real MNOs keep the seed.
 */
@SpringBootTest(
    properties = {
      "mw.ussd.mno.itmenu.transport=HTTP",
      "mw.ussd.mno.itmenu.short-codes=*268#",
      "mw.ussd.mno.itmenu.max-length-gsm=182",
      "mw.ussd.mno.itmenu.max-length-ucs2=70",
      "mw.ussd.mno.itmenu.session-timeout=120s",
      "mw.ussd.mno.itmenu.overflow=PAGE",
      "mw.ussd.mno.itmenu.continue-flag=CON",
      "mw.ussd.mno.itmenu.end-flag=END"
    })
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
@DirtiesContext
class MenuPublisherIT {

  static final String CUSTOMER = "01711000001";
  static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final MenuPublisher publisher;
  private final MenuRepository menus;
  private final MockMvcTester http;
  private final JsonMapper json;
  private final JdbcTemplate jdbc;

  @Autowired
  MenuPublisherIT(MenuPublisher publisher, MenuRepository menus, MockMvcTester http, JsonMapper json, JdbcTemplate jdbc) {
    this.publisher = publisher;
    this.menus = menus;
    this.http = http;
    this.json = json;
    this.jdbc = jdbc;
  }

  /** The shipped main menu with its main screen title replaced. */
  private Map<String, Object> seedWithTitle(String bn, String en) {
    ObjectNode tree = (ObjectNode) json.readTree(MenuParser.resource("menus/main-v2.json"));
    ((ObjectNode) tree.path("nodes").path("main").path("text")).put("bn", bn).put("en", en);
    return json.convertValue(tree, MAP);
  }

  private static ConfigChangedEvent event(String menuId, long version, Map<String, Object> tree, String mno, Instant goLive) {
    return new ConfigChangedEvent(
        1, "ussd-menu/" + menuId, ConfigChangedEvent.ConfigType.USSD_MENU, ConfigChangedEvent.Change.UPSERT, version,
        Map.of("menuId", menuId, "version", version, "menu", tree,
            "activations", List.of(Map.of("mno", mno, "goLiveAt", goLive.toString()))),
        Instant.now(), "ops-maker01", "ops-checker01", "corr-it");
  }

  private JsonNode step(String session, String type, String input) throws Exception {
    ObjectNode body = json.createObjectNode();
    body.put("sessionId", session).put("msisdn", CUSTOMER).put("serviceCode", "*268#").put("type", type).put("input", input);
    var result = http.post().uri("/ussd/itmenu").contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body)).exchange();
    assertThat(result).hasStatusOk();
    return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  void anApprovedTextChangeShowsInTheNextSessionButNotInARunningOne() throws Exception {
    String running = "it-" + UUID.randomUUID();
    assertThat(step(running, "BEGIN", "").path("message").asString()).startsWith("Bits\n1 সেন্ড মানি");

    publisher.onConfigChanged(event("main", 7, seedWithTitle("Bits নতুন", "Bits new"), "itmenu", Instant.now()));

    assertThat(step(running, "CONTINUE", "9").path("message").asString())
        .as("the running session finishes on the version it started on")
        .startsWith("Bits\n1 Send Money");
    assertThat(step("it-" + UUID.randomUUID(), "BEGIN", "").path("message").asString())
        .as("the next session gets the published version")
        .startsWith("Bits নতুন\n1 সেন্ড মানি");
    assertThat(menus.active("itmenu", Instant.now()).orElseThrow().version()).isGreaterThanOrEqualTo(MenuPublisher.FIRST_PUBLISHED);
  }

  @Test
  void aRedeliveredOrOlderVersionIsStoredOnceAndAFutureGoLiveWaits() {
    String menuId = "it-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> tree = seedWithTitle("Bits ২", "Bits 2");
    publisher.onConfigChanged(event(menuId, 2, tree, "itmenu", Instant.now().plus(Duration.ofHours(1))));
    publisher.onConfigChanged(event(menuId, 2, tree, "itmenu", Instant.now().plus(Duration.ofHours(1))));
    publisher.onConfigChanged(event(menuId, 1, seedWithTitle("Bits ১", "Bits 1"), "itmenu", Instant.now()));

    assertThat(jdbc.queryForList("select config_version from menu where menu_id = ?", Long.class, menuId)).containsExactly(2L);
    assertThat(menus.active("itmenu", Instant.now()).orElseThrow().menuId()).isNotEqualTo(menuId);
    assertThat(menus.active("itmenu", Instant.now().plus(Duration.ofHours(2))).orElseThrow().menuId()).isEqualTo(menuId);
  }

  @Test
  void anUnusableTreeOrAnUnknownMnoChangesNothing() {
    String menuId = "it-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> tree = seedWithTitle("Bits", "Bits");
    ObjectNode broken = json.valueToTree(tree);
    ((ObjectNode) broken.path("nodes").path("cancelled").path("text")).remove("en");
    publisher.onConfigChanged(event(menuId, 1, json.convertValue(broken, MAP), "itmenu", Instant.now()));
    assertThat(menus.latestConfigVersion(menuId)).isEmpty();

    publisher.onConfigChanged(event(menuId, 2, tree, "airtel", Instant.now()));
    assertThat(menus.latestConfigVersion(menuId)).contains(2L);
    assertThat(menus.hasActivation("airtel")).isFalse();
  }
}
