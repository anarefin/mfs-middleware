package mfs.mw.adapter.remit.partner.mapping;

import com.dashjoin.jsonata.Jsonata;

/**
 * A request or response mapping written in JSONata (spec 12.1 layer 2), compiled once when its
 * partner record is loaded and evaluated over plain JSON values (maps, lists, strings, numbers,
 * booleans). One instance is shared by concurrent calls: dashjoin's {@code evaluate} runs on a
 * per-thread copy of the parsed expression ({@code Jsonata.getPerThreadInstance}). Camel's own
 * {@code jsonata:} component parses the expression on every exchange, so the mw-* Kamelets use
 * this instead (ticket 82).
 */
public final class CompiledMapping {

  private final Jsonata expression;

  private CompiledMapping(Jsonata expression) {
    this.expression = expression;
  }

  /**
   * @throws IllegalArgumentException when the expression does not compile
   */
  public static CompiledMapping compile(String source) {
    try {
      return new CompiledMapping(Jsonata.jsonata(source));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  /**
   * Evaluates the mapping over {@code input}.
   *
   * @return the JSON value it produces, or null for an undefined result
   * @throws MappingException when the evaluation fails
   */
  public Object evaluate(Object input) {
    try {
      return expression.evaluate(input);
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
