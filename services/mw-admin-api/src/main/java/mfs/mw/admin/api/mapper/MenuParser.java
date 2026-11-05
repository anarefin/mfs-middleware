package mfs.mw.admin.api.mapper;

import java.util.Map;
import mfs.mw.admin.api.dto.Menu;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * USSD menu JSON to the menu model (copied from mw-ussd's {@code mapper/MenuParser}, ticket 39).
 * Optional flags of the menu JSON are primitives: absent means false / 0, so the reader turns
 * FAIL_ON_NULL_FOR_PRIMITIVES off.
 */
public final class MenuParser {

  private MenuParser() {}

  public static Menu parse(JsonMapper json, String tree) {
    return json.readerFor(Menu.class).without(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).readValue(tree);
  }

  /** A tree held as a JSON object map (a request body, a change request payload). */
  public static Menu parse(JsonMapper json, Map<String, Object> tree) {
    return parse(json, json.writeValueAsString(tree));
  }
}
