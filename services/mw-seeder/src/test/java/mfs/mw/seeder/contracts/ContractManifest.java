package mfs.mw.seeder.contracts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * This module's {@code contracts.yml} (ticket 53): the contracts it produces and consumes, paths
 * relative to {@code contracts/}; {@code <schema>#<Msg>} names one message type of a multi-type
 * envelope topic. The test JVM runs in the module folder, so the file is {@code contracts.yml}.
 */
public record ContractManifest(
    List<String> producedEvents, List<String> producedApis, List<String> consumedEvents, List<String> consumedApis) {

  public static ContractManifest load() {
    String text;
    try {
      text = Files.readString(Path.of("contracts.yml"));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the module's contracts.yml", e);
    }
    Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
    return new ContractManifest(
        list(root, "produces", "events"), list(root, "produces", "apis"),
        list(root, "consumes", "events"), list(root, "consumes", "apis"));
  }

  @SuppressWarnings("unchecked")
  private static List<String> list(Map<String, Object> root, String side, String kind) {
    Map<String, Object> s = (Map<String, Object>) root.get(side);
    if (s == null) {
      throw new IllegalStateException("contracts.yml has no '" + side + "'");
    }
    if (!s.containsKey(kind)) {
      throw new IllegalStateException("contracts.yml has no '" + side + "." + kind + "'");
    }
    Object v = s.get(kind);
    return v == null ? List.of() : List.copyOf((List<String>) v);
  }

  /** The schema path of an entry, without its {@code #<Msg>}. */
  public static String schemaPath(String entry) {
    int i = entry.indexOf('#');
    return i < 0 ? entry : entry.substring(0, i);
  }

  /** The message type of a {@code #<Msg>} entry, or null. */
  public static String messageType(String entry) {
    int i = entry.indexOf('#');
    return i < 0 ? null : entry.substring(i + 1);
  }

  /**
   * The {@code valid*.json} samples of an event entry: {@code <dir>/samples/<topic>/[<Msg>/]}; for an
   * envelope entry without {@code #<Msg>}, those of every message type folder.
   */
  public static List<Path> validSamples(String entry) {
    String schema = schemaPath(entry);
    int slash = schema.lastIndexOf('/');
    String topic = schema.substring(slash + 1).replace(".schema.json", "");
    String dir = schema.substring(0, slash) + "/samples/" + topic;
    String msg = messageType(entry);
    if (msg != null) {
      return ContractSchemas.samples(dir + "/" + msg, "valid");
    }
    List<Path> out = new ArrayList<>(ContractSchemas.samples(dir, "valid"));
    try (Stream<Path> sub = Files.list(ContractSchemas.contractsDir().resolve(dir))) {
      for (Path d : sub.filter(Files::isDirectory).sorted().toList()) {
        out.addAll(ContractSchemas.samples(dir + "/" + d.getFileName(), "valid"));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }
}
