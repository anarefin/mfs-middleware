package mfs.mw.ussd.contracts;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Schemas inside the OpenAPI 3.1 files of {@code contracts/api/} (JSON Schema 2020-12). The YAML
 * document is read from {@code mwContractsDir}, turned into JSON and registered under its
 * contracts id ({@link ContractSchemas#ID_BASE} + path) plus {@code .json} (networknt would
 * otherwise parse a {@code .yaml} id with Jackson YAML, which is not on the classpath); a schema
 * is a {@code $ref} into it, so the document's own {@code #/components/...} refs resolve.
 * Nothing is fetched over the network.
 */
public final class OpenApiSchemas {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12, builder -> builder.schemas(OpenApiSchemas::read));

  private static final String YAML_AS_JSON = ".yaml.json";

  private OpenApiSchemas() {}

  private static String read(String id) {
    if (!id.startsWith(ContractSchemas.ID_BASE)) {
      return null;
    }
    boolean yaml = id.endsWith(YAML_AS_JSON);
    try {
      String text =
          Files.readString(
              ContractSchemas.fileFor(yaml ? id.substring(0, id.length() - ".json".length()) : id));
      if (!yaml) {
        return text;
      }
      Map<String, Object> doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
      return JSON.writeValueAsString(doc);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read contract " + id, e);
    }
  }

  /** {@code #/components/schemas/<name>} of the OpenAPI file {@code api} (path under contracts/). */
  public static Schema component(String api, String name) {
    return at(api, "components", "schemas", name);
  }

  /** The schema at the JSON pointer made of {@code tokens} in the OpenAPI file {@code api}. */
  public static Schema at(String api, String... tokens) {
    StringBuilder pointer = new StringBuilder();
    for (String token : tokens) {
      pointer.append('/').append(escape(token));
    }
    ObjectNode ref = JSON.createObjectNode();
    ref.put("$ref", ContractSchemas.ID_BASE + api + (api.endsWith(".yaml") ? ".json" : "") + "#" + pointer);
    return REGISTRY.getSchema(ref);
  }

  /** A JSON pointer token (RFC 6901), percent-encoded where a URI fragment needs it. */
  private static String escape(String token) {
    return token
        .replace("~", "~0")
        .replace("/", "~1")
        .replace("{", "%7B")
        .replace("}", "%7D");
  }
}
