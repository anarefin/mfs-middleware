package mfs.mw.ussd.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import mfs.mw.ussd.dto.Menu;
import mfs.mw.ussd.mapper.MenuParser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MenuValidatorTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  static Menu menu(String nodes) {
    return MenuParser.parse(JSON, "{\"menuId\":\"t\",\"version\":1,\"start\":\"a\",\"nodes\":{" + nodes + "}}");
  }

  @Test
  void theSeededMenuIsValid() {
    assertThat(MenuValidator.problems(MenuParser.parseResource(JSON, "menus/main-v1.json"))).isEmpty();
  }

  @Test
  void aReferenceToAMissingNodeIsRefused() {
    Menu m = menu("\"a\":{\"type\":\"MENU\",\"options\":[{\"key\":\"1\",\"next\":\"nowhere\"}]}");
    assertThat(MenuValidator.problems(m)).containsExactly("a: option 1 points to missing node nowhere");
  }

  @Test
  void aPinInputMustLeadStraightToAnActionAndIsNeverStored() {
    Menu m =
        menu(
            "\"a\":{\"type\":\"INPUT\",\"validator\":{\"type\":\"pin\"},\"store\":\"pin\",\"next\":\"b\"},"
                + "\"b\":{\"type\":\"END\",\"text\":{\"en\":\"x\"}}");
    assertThat(MenuValidator.problems(m))
        .containsExactlyInAnyOrder(
            "a: a PIN input is never stored", "a: a PIN input must lead straight to an ACTION");
  }

  @Test
  void unknownValidatorsFlowsAndBadPatternsAreRefused() {
    Menu m =
        menu(
            "\"a\":{\"type\":\"INPUT\",\"validator\":{\"type\":\"email\"},\"store\":\"x\",\"next\":\"b\"},"
                + "\"b\":{\"type\":\"INPUT\",\"validator\":{\"type\":\"regex\",\"pattern\":\"[\"},\"store\":\"y\",\"next\":\"c\"},"
                + "\"c\":{\"type\":\"ACTION\",\"flow\":\"LOAN\",\"onSuccess\":\"d\",\"onFailure\":\"d\"},"
                + "\"d\":{\"type\":\"END\",\"text\":{\"en\":\"x\"}}");
    assertThat(MenuValidator.problems(m))
        .anyMatch(p -> p.startsWith("a: a validator of"))
        .contains("b: regex pattern does not compile")
        .anyMatch(p -> p.startsWith("c: flow must be one of"));
  }

  @Test
  void requireValidThrowsWithTheProblems() {
    assertThatThrownBy(() -> MenuValidator.requireValid(menu("")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("start node a does not exist");
  }
}
