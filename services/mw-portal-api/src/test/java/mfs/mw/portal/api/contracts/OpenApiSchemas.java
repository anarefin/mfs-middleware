package mfs.mw.portal.api.contracts;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Schemas inside the OpenAPI 3.1 files of {@code contracts/api/} (JSON Schema 2020-12). A document
 * is read from {@code mwContractsDir}, turned from YAML into JSON and registered under its contracts
 * id plus {@code .json} (so the validator never needs a YAML parser), and a schema is addressed by
 * JSON pointer: a component ({@link #component}) or an inline response body ({@link #response}).
 * Internal {@code $ref}s ({@code #/components/schemas/X}) resolve within the document.
 */
public final class OpenApiSchemas {

  private static final String SUFFIX = ".json";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, b -> b.schemas(OpenApiSchemas::read));

  private OpenApiSchemas() {}

  private static String read(String id) {
    if (!id.startsWith(ContractSchemas.ID_BASE + "api/") || !id.endsWith(SUFFIX)) {
      return null;
    }
    try {
      String yaml = Files.readString(ContractSchemas.fileFor(id.substring(0, id.length() - SUFFIX.length())));
      Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
      return JSON.writeValueAsString(doc);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read contract " + id, e);
    }
  }

  /** {@code #/components/schemas/<name>} of the OpenAPI file {@code api} (path under contracts/). */
  public static Schema component(String api, String name) {
    return pointer(api, "/components/schemas/" + name);
  }

  /** The {@code application/json} body schema of response {@code status} of {@code method path}. */
  public static Schema response(String api, String path, String method, String status) {
    return pointer(
        api, "/paths/" + escape(path) + "/" + method + "/responses/" + status + "/content/application~1json/schema");
  }

  /** The schema at JSON pointer {@code pointer} in the OpenAPI file {@code api}. */
  public static Schema pointer(String api, String pointer) {
    return REGISTRY.getSchema(SchemaLocation.of(ContractSchemas.ID_BASE + api + SUFFIX + "#" + pointer));
  }

  private static String escape(String token) {
    return token.replace("~", "~0").replace("/", "~1");
  }
}
