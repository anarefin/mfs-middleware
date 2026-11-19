package mfs.mw.crypto.contracts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drift between the OpenAPI document springdoc generates from the controllers and the contract
 * files this module owns (ticket 101, PLT-050). {@code contracts/} stays the source of truth; this
 * only proves the code still matches it. The owned files together form one surface, compared per
 * operation (method + path; path variables are compared by position, their names do not matter):
 *
 * <ul>
 *   <li>an operation on one side only is drift;
 *   <li>the required query and header parameters must be the same set (header names compared
 *       case-insensitively; {@code $ref}s and path-level parameters resolved);
 *   <li>every response code the code declares must be declared by the contract (the contract may
 *       list more: error codes come from the global handler, which springdoc does not see).
 * </ul>
 */
public final class ContractDrift {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> METHODS = List.of("get", "put", "post", "delete", "patch", "head", "options");

  private ContractDrift() {}

  private record Operation(Set<String> required, Set<String> responses) {}

  /** The differences, one line each; empty when the code matches the contracts. */
  public static List<String> differences(JsonNode generated, List<JsonNode> contracts) {
    Map<String, Operation> code = operations(generated);
    Map<String, Operation> contract = new TreeMap<>();
    contracts.forEach(c -> contract.putAll(operations(c)));
    List<String> out = new ArrayList<>();
    Set<String> keys = new TreeSet<>(code.keySet());
    keys.addAll(contract.keySet());
    for (String key : keys) {
      Operation inCode = code.get(key);
      Operation inContract = contract.get(key);
      if (inContract == null) {
        out.add(key + ": in code, not in the contract");
      } else if (inCode == null) {
        out.add(key + ": in the contract, not in code");
      } else {
        if (!inCode.required().equals(inContract.required())) {
          out.add(key + ": required parameters differ: code " + inCode.required()
              + ", contract " + inContract.required());
        }
        for (String status : inCode.responses()) {
          if (!inContract.responses().contains(status)) {
            out.add(key + ": response " + status + " in code, contract has " + inContract.responses());
          }
        }
      }
    }
    return out;
  }

  /** An OpenAPI YAML file under {@code mwContractsDir}, e.g. {@code api/mw-x/x.v1.openapi.yaml}, as JSON. */
  public static JsonNode contract(String path) {
    Path file = ContractSchemas.contractsDir().resolve(path);
    try {
      Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
      return JSON.valueToTree(doc);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read contract " + path, e);
    }
  }

  /** The OpenAPI files this module owns: {@code produces.apis} of its {@code contracts.yml}. */
  @SuppressWarnings("unchecked")
  public static List<String> ownedApis() {
    try {
      Map<String, Object> root =
          new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(Path.of("contracts.yml")));
      Object apis = ((Map<String, Object>) root.get("produces")).get("apis");
      return apis == null ? List.of() : List.copyOf((List<String>) apis);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the module's contracts.yml", e);
    }
  }

  private static Map<String, Operation> operations(JsonNode doc) {
    Map<String, Operation> ops = new TreeMap<>();
    JsonNode paths = doc.path("paths");
    for (String path : paths.propertyNames()) {
      JsonNode item = paths.get(path);
      for (String method : METHODS) {
        JsonNode op = item.get(method);
        if (op == null) {
          continue;
        }
        Set<String> required = new TreeSet<>();
        List<JsonNode> params = new ArrayList<>();
        item.path("parameters").forEach(params::add);
        op.path("parameters").forEach(params::add);
        for (JsonNode p : params) {
          JsonNode param = resolve(doc, p);
          String in = param.path("in").asString("");
          if (param.path("required").asBoolean(false) && ("query".equals(in) || "header".equals(in))) {
            String name = param.path("name").asString("");
            required.add(in + ":" + ("header".equals(in) ? name.toLowerCase(Locale.ROOT) : name));
          }
        }
        Set<String> responses = new TreeSet<>();
        for (String status : op.path("responses").propertyNames()) {
          if (!"default".equals(status)) {
            responses.add(status);
          }
        }
        ops.put(method.toUpperCase(Locale.ROOT) + " " + path.replaceAll("\\{[^}]*}", "{}"),
            new Operation(required, responses));
      }
    }
    return ops;
  }

  private static JsonNode resolve(JsonNode doc, JsonNode node) {
    JsonNode ref = node.get("$ref");
    if (ref == null || !ref.asString("").startsWith("#/")) {
      return node;
    }
    return resolve(doc, doc.at(ref.asString().substring(1)));
  }
}
