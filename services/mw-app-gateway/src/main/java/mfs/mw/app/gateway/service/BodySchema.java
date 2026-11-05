package mfs.mw.app.gateway.service;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * The schema check of one {@code app.v1} JSON request body (spec 8.3, ticket 13; moved here from
 * the APISIX edge, ticket 29, and extended to every body, M3 gate decision A6). APISIX
 * request-validation forwards a body it validated re-encoded, so the gateway could not hash the
 * bytes the device signed; it validates the exact bytes it hashes and parses. Each schema is a
 * {@code derived-schemas/*.json} resource ({@link #TRANSACTION}, {@link #DEVICE_OTP}, {@link
 * #DEVICE_BINDING}, {@link #FETCH_BILL}), derived from the contract's request schema by {@code
 * scripts/derived-schemas.sh} ({@code make contracts-lint} fails when one drifts); none has a
 * pattern or format, so no message repeats a request value.
 *
 * <p>Duplicate keys are refused (two JSON parsers may keep different values; the edge's re-encoding
 * used to settle that), and so is trailing content. Every failure is 400 MW-VAL-001, as the edge
 * answered.
 */
public class BodySchema {

  /** {@code POST /app/v1/transactions}, from {@code TransactionRequest}. */
  public static final String TRANSACTION = "derived-schemas/app-v1-transaction-request.json";
  /** {@code POST /app/v1/devices/otp} (optional body), from {@code DeviceOtpRequest}. */
  public static final String DEVICE_OTP = "derived-schemas/app-v1-device-otp-request.json";
  /** {@code POST /app/v1/devices}, from {@code DeviceBindingRequest}. */
  public static final String DEVICE_BINDING = "derived-schemas/app-v1-device-binding-request.json";
  /** {@code POST /app/v1/billers/{code}/fetch-bill}, from {@code FetchBillRequest}. */
  public static final String FETCH_BILL = "derived-schemas/app-v1-fetch-bill-request.json";

  private final ObjectReader reader;
  private final Schema schema;

  public BodySchema(JsonMapper json, String resource) {
    this.reader =
        json.reader()
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(load(resource));
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
      throw refused(errors.stream().map(BodySchema::detail).toList());
    }
  }

  private static ErrorDetail detail(Error e) {
    // instance location as a JSON pointer ("/amount/valueMinor") -> the dotted field name the
    // gateway's other validation errors use ("amount.valueMinor")
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

  private static InputStream load(String resource) {
    InputStream in = BodySchema.class.getClassLoader().getResourceAsStream(resource);
    if (in == null) {
      throw new UncheckedIOException(new IOException("no " + resource + " on the classpath"));
    }
    return in;
  }
}
