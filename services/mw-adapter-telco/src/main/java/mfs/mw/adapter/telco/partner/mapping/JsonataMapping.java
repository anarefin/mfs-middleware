package mfs.mw.adapter.telco.partner.mapping;

import com.dashjoin.jsonata.Jsonata;

/**
 * A request or response mapping written in JSONata (spec 12.1 layer 2), evaluated over plain
 * JSON values (maps, lists, strings, numbers, booleans). The source is checked when the partner
 * config is loaded; each evaluation parses it again, so one mapping is safe to share between
 * concurrent calls whatever the thread-safety of the evaluator.
 */
public final class JsonataMapping {

  private final String source;

  private JsonataMapping(String source) {
    this.source = source;
  }

  /**
   * @throws IllegalArgumentException when the expression does not compile
   */
  public static JsonataMapping compile(String source) {
    try {
      Jsonata.jsonata(source);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    return new JsonataMapping(source);
  }

  /**
   * Evaluates the mapping over {@code input}.
   *
   * @return the JSON value it produces, or null for an undefined result
   * @throws MappingException when the evaluation fails
   */
  public Object evaluate(Object input) {
    try {
      return Jsonata.jsonata(source).evaluate(input);
    } catch (RuntimeException e) {
      throw new MappingException(e.getMessage(), e);
    }
  }

  /** A mapping that compiled but failed on this input. */
  public static class MappingException extends RuntimeException {
    public MappingException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
