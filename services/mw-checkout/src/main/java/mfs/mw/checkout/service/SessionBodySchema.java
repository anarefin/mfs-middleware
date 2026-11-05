package mfs.mw.checkout.service;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import mfs.mw.checkout.dto.ErrorDetail;
import mfs.mw.checkout.exception.ErrorCodes;
import mfs.mw.checkout.exception.MwException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * The schema check of {@code POST /checkout/v1/sessions} bodies (spec 8.3; moved here from the
 * APISIX edge: body schemas are checked in the service, never at the edge, M3 gate decision).
 * It runs on the exact bytes the controller then binds (copied from mw-app-gateway's
 * SubmitBodySchema). The schema is {@code derived-schemas/checkout-v1-create-session.json},
 * derived from the contract's {@code CreateSessionRequest} by {@code scripts/derived-schemas.sh}
 * ({@code make contracts-lint} fails when it drifts); it has no pattern or format, so no message
 * repeats a request value (the URL and order id patterns stay on {@code dto/CreateSessionRequest}).
 *
 * <p>Duplicate keys are refused (two JSON parsers may keep different values), and so is trailing
 * content. Every failure is 400 MW-VAL-001.
 */
public class SessionBodySchema {

  static final String RESOURCE = "derived-schemas/checkout-v1-create-session.json";

  private final ObjectReader reader;
  private final Schema schema;

  public SessionBodySchema(JsonMapper json) {
    this.reader =
        json.reader()
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(load());
  }

  /** Returns when {@code body} is one JSON object matching the schema; else 400 MW-VAL-001. */
  public void check(byte[] body) {
    JsonNode tree;
    try {
      tree = reader.readTree(body);
    } catch (JacksonException e) {
      throw refused(List.of(new ErrorDetail("body", "not a single JSON object with unique keys")));
    }
    if (tree == null || !tree.isObject()) {
      throw refused(List.of(new ErrorDetail("body", "not a single JSON object with unique keys")));
    }
    List<Error> errors = schema.validate(tree);
    if (!errors.isEmpty()) {
      throw refused(errors.stream().map(SessionBodySchema::detail).toList());
    }
  }

  private static ErrorDetail detail(Error e) {
    // instance location as a JSON pointer ("/amount/valueMinor") -> "amount.valueMinor"
    String at = e.getInstanceLocation() == null ? "" : e.getInstanceLocation().toString();
    String field = at.replaceFirst("^[$/.]+", "").replace('/', '.');
    if (e.getProperty() != null && !field.endsWith(e.getProperty())) {
      field = field.isEmpty() ? e.getProperty() : field + "." + e.getProperty();
    }
    // the derived schema carries no pattern/format, so the message names keys and allowed values only
    return new ErrorDetail(field.isEmpty() ? "body" : field, e.getMessage());
  }

  private static MwException refused(List<ErrorDetail> details) {
    return new MwException(ErrorCodes.VAL_INVALID, "Request body does not match the schema").withDetails(details);
  }

  private static InputStream load() {
    InputStream in = SessionBodySchema.class.getClassLoader().getResourceAsStream(RESOURCE);
    if (in == null) {
      throw new UncheckedIOException(new IOException("no " + RESOURCE + " on the classpath"));
    }
    return in;
  }
}
