package mfs.mw.adapter.telco.contracts;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A contract schema that validates this service's (Jackson 3) JSON. The validator is networknt
 * json-schema-validator 2.0.1, the version camel-json-validator ships (Jackson 2): one artifact can
 * have only one version on the classpath, and the runtime one wins (ticket 82). The document is
 * handed over as JSON text, so no Jackson 2 type leaks into the tests.
 */
public record JsonSchema(Schema schema) {

  /** Every error of {@code document} against this schema; empty when it is valid. */
  public List<Error> validate(JsonNode document) {
    return schema.validate(document.toString(), InputFormat.JSON);
  }
}
