package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import mfs.mw.admin.api.dto.Menu;
import mfs.mw.admin.api.mapper.MenuParser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The maker-time check of a USSD menu tree (ticket 39): the same rules mw-ussd applies before it
 * uses a menu, plus complete bn and en texts, so an operator learns of a broken tree before a
 * checker ever sees it.
 */
class MenuValidatorTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String MESSAGES =
      "\"messages\":{"
          + "\"invalid\":{\"bn\":\"ভুল\",\"en\":\"Invalid\"},\"tooManyAttempts\":{\"bn\":\"অনেক\",\"en\":\"Too many\"},"
          + "\"expired\":{\"bn\":\"শেষ\",\"en\":\"Expired\"},\"processing\":{\"bn\":\"চলছে\",\"en\":\"Processing\"},"
          + "\"unavailable\":{\"bn\":\"বন্ধ\",\"en\":\"Unavailable\"},\"more\":{\"bn\":\"0 আরও\",\"en\":\"0 More\"}}";

  static Menu menu(String nodes) {
    return MenuParser.parse(
        JSON, "{\"menuId\":\"t\",\"version\":1,\"start\":\"a\"," + MESSAGES + ",\"nodes\":{" + nodes + "}}");
  }

  static String seed() throws IOException {
    try (InputStream in = MenuValidatorTest.class.getClassLoader().getResourceAsStream("menus/main-v2.json")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void theShippedMainMenuIsValid() throws IOException {
    assertThat(MenuValidator.problems(MenuParser.parse(JSON, seed()))).isEmpty();
  }

  @Test
  void aDanglingNodeIsRefused() {
    Menu m =
        menu(
            "\"a\":{\"type\":\"MENU\",\"text\":{\"bn\":\"ক\",\"en\":\"A\"},"
                + "\"options\":[{\"key\":\"1\",\"label\":{\"bn\":\"এক\",\"en\":\"One\"},\"next\":\"nowhere\"}]}");
    assertThat(MenuValidator.problems(m)).containsExactly("a: option 1 points to missing node nowhere");
  }

  @Test
  void aMissingBanglaOrEnglishTextIsRefused() {
    Menu m =
        menu(
            "\"a\":{\"type\":\"MENU\",\"text\":{\"en\":\"A\"},"
                + "\"options\":[{\"key\":\"1\",\"label\":{\"bn\":\"এক\",\"en\":\" \"},\"next\":\"b\"}]},"
                + "\"b\":{\"type\":\"END\",\"text\":{\"bn\":\"শেষ\"}}");
    assertThat(MenuValidator.problems(m))
        .containsExactlyInAnyOrder(
            "a: text needs both bn and en", "a: option 1 label needs both bn and en", "b: text needs both bn and en");
  }

  @Test
  void aNodeWithoutTextAndAMissingEngineMessageAreRefused() {
    Menu m =
        MenuParser.parse(
            JSON,
            "{\"menuId\":\"t\",\"version\":1,\"start\":\"a\",\"messages\":{\"invalid\":{\"bn\":\"ভুল\",\"en\":\"Invalid\"}},"
                + "\"nodes\":{\"a\":{\"type\":\"END\"}}}");
    assertThat(MenuValidator.problems(m))
        .contains("a: text is required", "message expired needs both bn and en", "message more needs both bn and en");
  }

  @Test
  void aPinInputMustLeadStraightToAnActionAndIsNeverStored() {
    Menu m =
        menu(
            "\"a\":{\"type\":\"INPUT\",\"text\":{\"bn\":\"পিন\",\"en\":\"PIN\"},\"validator\":{\"type\":\"pin\"},"
                + "\"store\":\"pin\",\"next\":\"b\"},"
                + "\"b\":{\"type\":\"END\",\"text\":{\"bn\":\"শেষ\",\"en\":\"End\"}}");
    assertThat(MenuValidator.problems(m))
        .containsExactlyInAnyOrder("a: a PIN input is never stored", "a: a PIN input must lead straight to an ACTION");
  }

  @Test
  void unknownFlowsAndBadPatternsAreRefused() {
    Menu m =
        menu(
            "\"a\":{\"type\":\"INPUT\",\"text\":{\"bn\":\"ক\",\"en\":\"A\"},"
                + "\"validator\":{\"type\":\"regex\",\"pattern\":\"[\"},\"store\":\"y\",\"next\":\"c\"},"
                + "\"c\":{\"type\":\"ACTION\",\"flow\":\"LOAN\",\"onSuccess\":\"d\",\"onFailure\":\"d\"},"
                + "\"d\":{\"type\":\"END\",\"text\":{\"bn\":\"শেষ\",\"en\":\"End\"}}");
    assertThat(MenuValidator.problems(m))
        .contains("a: regex pattern does not compile")
        .anyMatch(p -> p.startsWith("c: flow must be one of"));
  }
}
