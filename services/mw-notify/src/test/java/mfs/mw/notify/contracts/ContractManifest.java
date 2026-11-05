package mfs.mw.notify.contracts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * This module's {@code contracts.yml} (ticket 53): the contracts it produces and consumes, paths
 * relative to {@code contracts/}, {@code #<Msg>} naming one message type of a multi-type topic.
 * Read from the module folder, the working directory of the test JVM.
 */
public final class ContractManifest {

  private static final Path FILE = Path.of("contracts.yml");

  private ContractManifest() {}

  /** Entries under {@code produces.events}. */
  public static List<String> producedEvents() {
    return list("produces", "events");
  }

  /** Entries under {@code consumes.events}. */
  public static List<String> consumedEvents() {
    return list("consumes", "events");
  }

  /** Entries under {@code produces.apis}. */
  public static List<String> producedApis() {
    return list("produces", "apis");
  }

  /** Entries under {@code consumes.apis}. */
  public static List<String> consumedApis() {
    return list("consumes", "apis");
  }

  /** The schema path of an entry (before {@code #}). */
  public static String schemaPath(String entry) {
    int hash = entry.indexOf('#');
    return hash < 0 ? entry : entry.substring(0, hash);
  }

  /** The message type of an entry (after {@code #}), or null for a single-type topic. */
  public static String messageType(String entry) {
    int hash = entry.indexOf('#');
    return hash < 0 ? null : entry.substring(hash + 1);
  }

  /** The samples folder of an entry: {@code <dir>/samples/<topic>[/<Msg>]} under contracts/. */
  public static String samplesDir(String entry) {
    String schema = schemaPath(entry);
    int slash = schema.lastIndexOf('/');
    String topic = schema.substring(slash + 1).replace(".schema.json", "");
    String dir = schema.substring(0, slash) + "/samples/" + topic;
    String msg = messageType(entry);
    return msg == null ? dir : dir + "/" + msg;
  }

  @SuppressWarnings("unchecked")
  private static List<String> list(String side, String kind) {
    Map<String, Object> doc;
    try {
      doc = new Yaml().load(Files.readString(FILE));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + FILE.toAbsolutePath(), e);
    }
    Map<String, Object> section = (Map<String, Object>) doc.get(side);
    if (section == null || !section.containsKey(kind)) {
      throw new IllegalStateException("contracts.yml has no " + side + "." + kind);
    }
    List<String> entries = (List<String>) section.get(kind);
    return entries == null ? List.of() : List.copyOf(entries);
  }
}
