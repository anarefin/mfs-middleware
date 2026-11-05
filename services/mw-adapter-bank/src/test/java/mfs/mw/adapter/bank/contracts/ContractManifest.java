package mfs.mw.adapter.bank.contracts;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * This module's {@code contracts.yml} (ticket 53): the event schemas and APIs it produces and
 * consumes, paths relative to {@code contracts/}, {@code #<Msg>} naming one message type of a
 * multi-type topic. Read from the module folder, the working directory of the test JVM.
 */
public record ContractManifest(
    List<String> producedEvents, List<String> producedApis, List<String> consumedEvents, List<String> consumedApis) {

  public static final Path FILE = Path.of("contracts.yml");

  /** One manifest entry split into its schema path and optional message type. */
  public record Entry(String path, String msg) {

    public static Entry of(String entry) {
      int hash = entry.indexOf('#');
      return hash < 0 ? new Entry(entry, null) : new Entry(entry.substring(0, hash), entry.substring(hash + 1));
    }

    /** {@code events/<owner>/<topic>.schema.json} -> {@code <topic>}. */
    public String topic() {
      String file = path.substring(path.lastIndexOf('/') + 1);
      return file.substring(0, file.length() - ".schema.json".length());
    }

    /** {@code events/<owner>/samples/<topic>[/<Msg>]}. */
    public String samplesDir() {
      String dir = path.substring(0, path.lastIndexOf('/'));
      return dir + "/samples/" + topic() + (msg == null ? "" : "/" + msg);
    }
  }

  public static ContractManifest load() {
    JsonNode root = YAMLMapper.builder().build().readTree(FILE.toFile());
    return new ContractManifest(
        list(root, "produces", "events"),
        list(root, "produces", "apis"),
        list(root, "consumes", "events"),
        list(root, "consumes", "apis"));
  }

  private static List<String> list(JsonNode root, String side, String kind) {
    JsonNode node = root.path(side).path(kind);
    if (!node.isArray()) {
      throw new IllegalStateException(FILE + ": " + side + "." + kind + " must be a list");
    }
    List<String> out = new ArrayList<>();
    node.forEach(n -> out.add(n.asString()));
    return List.copyOf(out);
  }
}
