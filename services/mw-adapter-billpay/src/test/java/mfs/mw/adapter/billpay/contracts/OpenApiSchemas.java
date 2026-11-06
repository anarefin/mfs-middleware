package mfs.mw.adapter.billpay.contracts;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Component schemas of an OpenAPI 3.1 file under {@code contracts/api/} (JSON Schema 2020-12). The
 * YAML document is read from {@code mwContractsDir}, turned into JSON and registered under an id
 * of its own, so {@code #/components/schemas/<Name>} and the refs inside it resolve from disk.
 */
public final class OpenApiSchemas {

  static final String ID_BASE = "https://openapi.bits.mw/";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final YAMLMapper YAML = YAMLMapper.builder().build();

  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, b -> b.schemas(OpenApiSchemas::read));

  private OpenApiSchemas() {}

  private static String read(String id) {
    if (!id.startsWith(ID_BASE)) {
      return null;
    }
    try {
      JsonNode doc = YAML.readTree(Files.readString(ContractSchemas.contractsDir().resolve(id.substring(ID_BASE.length()))));
      return JSON.writeValueAsString(doc);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read OpenAPI file " + id, e);
    }
  }

  /** {@code #/components/schemas/<name>} of the OpenAPI file at {@code path} under contracts/. */
  public static Schema component(String path, String name) {
    return REGISTRY.getSchema(SchemaLocation.of(ID_BASE + path + "#/components/schemas/" + name));
  }

  /** The OpenAPI document itself, for response schemas that are not components (arrays). */
  public static JsonNode document(String path) {
    try {
      return YAML.readTree(Files.readString(ContractSchemas.contractsDir().resolve(path)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
