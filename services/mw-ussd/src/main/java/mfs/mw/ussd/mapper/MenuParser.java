package mfs.mw.ussd.mapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import mfs.mw.ussd.dto.Menu;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Menu JSON (as stored in {@code ussd.menu.tree}) to the menu model. */
public final class MenuParser {

  private MenuParser() {}

  /**
   * Optional flags of the menu JSON ({@code switchLanguage}, {@code notSelf}, ...) are primitives:
   * absent means false / 0. Jackson 3 fails on an absent primitive by default
   * (FAIL_ON_NULL_FOR_PRIMITIVES), so the menu reader turns that off.
   */
  public static Menu parse(JsonMapper json, String tree) {
    return json.readerFor(Menu.class)
        .without(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .readValue(tree);
  }

  /** A menu from the classpath (the dev seed, tests). */
  public static Menu parseResource(JsonMapper json, String path) {
    return parse(json, resource(path));
  }

  public static String resource(String path) {
    try (InputStream in = MenuParser.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalArgumentException("no menu resource " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
