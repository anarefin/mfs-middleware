package mfs.mw.ussd.contracts;

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
 * This module's {@code contracts.yml} (ticket 53): the event schemas and APIs it produces and
 * consumes, as paths relative to {@code contracts/}; {@code <path>#<Msg>} names one message type
 * of a multi-type envelope topic. The test JVM runs in the module folder, so the file is
 * {@code contracts.yml} there.
 */
public record ContractManifest(
    List<String> producedEvents,
    List<String> producedApis,
    List<String> consumedEvents,
    List<String> consumedApis) {

  public static final Path FILE = Path.of("contracts.yml");

  /** Reads {@link #FILE}. */
  public static ContractManifest load() {
    Map<String, Object> root;
    try {
      root = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(FILE));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + FILE.toAbsolutePath(), e);
    }
    return new ContractManifest(
        list(root, "produces", "events"),
        list(root, "produces", "apis"),
        list(root, "consumes", "events"),
        list(root, "consumes", "apis"));
  }

  private static List<String> list(Map<String, Object> root, String side, String kind) {
    if (!(root.get(side) instanceof Map<?, ?> sideMap) || !sideMap.containsKey(kind)) {
      throw new IllegalStateException(FILE + ": missing " + side + "." + kind);
    }
    Object value = sideMap.get(kind);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> items)) {
      throw new IllegalStateException(FILE + ": " + side + "." + kind + " is not a list");
    }
    return items.stream().map(String::valueOf).toList();
  }

  /** The schema path of an entry (without {@code #<Msg>}). */
  public static String path(String entry) {
    int hash = entry.indexOf('#');
    return hash < 0 ? entry : entry.substring(0, hash);
  }

  /** The message type of an entry, or null when it names the whole topic. */
  public static String msg(String entry) {
    int hash = entry.indexOf('#');
    return hash < 0 ? null : entry.substring(hash + 1);
  }

  /**
   * The envelope field that carries the message type of a multi-type topic: {@code commandType}
   * ({@code core.command.v1}), {@code eventType} ({@code core.event.v1}), {@code family} (the
   * {@code mw.partner.*.v1} topics).
   */
  public static String typeField(String entry) {
    String topic = topic(entry);
    if (topic.equals("core.command.v1")) {
      return "commandType";
    }
    if (topic.equals("core.event.v1")) {
      return "eventType";
    }
    if (topic.startsWith("mw.partner.")) {
      return "family";
    }
    throw new IllegalArgumentException("not a multi-type topic: " + entry);
  }

  /** The topic of an event entry, e.g. {@code mw.txn.status.v1}. */
  public static String topic(String entry) {
    String file = Path.of(path(entry)).getFileName().toString();
    return file.substring(0, file.length() - ".schema.json".length());
  }

  /**
   * Every {@code valid*.json} (or, with {@code prefix} "invalid", {@code invalid*.json}) sample of
   * an event entry: {@code samples/<topic>/<Msg>/} for a {@code #<Msg>} entry, else the topic's
   * folder and every type folder under it.
   */
  public static List<Path> samples(String entry, String prefix) {
    String dir = Path.of(path(entry)).getParent().resolve("samples").resolve(topic(entry)).toString();
    String msg = msg(entry);
    if (msg != null) {
      return ContractSchemas.samples(dir + "/" + msg, prefix);
    }
    List<Path> all = new ArrayList<>(ContractSchemas.samples(dir, prefix));
    try (Stream<Path> children = Files.list(ContractSchemas.contractsDir().resolve(dir))) {
      for (Path sub : children.filter(Files::isDirectory).sorted().toList()) {
        all.addAll(ContractSchemas.samples(dir + "/" + sub.getFileName(), prefix));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return all;
  }
}
