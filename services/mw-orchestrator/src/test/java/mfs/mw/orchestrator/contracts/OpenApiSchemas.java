package mfs.mw.orchestrator.contracts;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import tools.jackson.databind.json.JsonMapper;

/**
 * Component schemas of an OpenAPI 3.1 contract under {@code contracts/api/} (JSON Schema 2020-12).
 * The YAML document is converted to JSON and registered under its contracts id, so {@code
 * #/components/schemas/<Name>} and the document's internal {@code $ref}s resolve from disk.
 */
public final class OpenApiSchemas {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12, builder -> builder.schemas(OpenApiSchemas::read));

  private OpenApiSchemas() {}

  /** The schema {@code #/components/schemas/<name>} of the OpenAPI file at {@code path} under contracts/. */
  public static Schema component(String path, String name) {
    return REGISTRY.getSchema(SchemaLocation.of(ContractSchemas.ID_BASE + path + "#/components/schemas/" + name));
  }

  /** The OpenAPI document of a contracts id, as JSON text; null for any other id. */
  private static String read(String id) {
    if (!id.startsWith(ContractSchemas.ID_BASE) || !(id.endsWith(".yaml") || id.endsWith(".yml"))) {
      return null;
    }
    try {
      Object doc = new Load(LoadSettings.builder().build()).loadFromString(Files.readString(ContractSchemas.fileFor(id)));
      return JSON.writeValueAsString(doc);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read contract " + id, e);
    }
  }
}
