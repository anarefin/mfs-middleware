package mfs.mw.data.export.contracts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** This module's {@code contracts.yml}. */
public final class ContractManifest {

  private static final Path FILE = Path.of("contracts.yml");

  private ContractManifest() {}

  public static List<String> producedEvents() {
    return list("produces", "events");
  }

  public static String schemaPath(String entry) {
    int hash = entry.indexOf('#');
    return hash < 0 ? entry : entry.substring(0, hash);
  }

  @SuppressWarnings("unchecked")
  private static List<String> list(String side, String kind) {
    Map<String, Object> doc;
    try {
      doc = new Yaml().load(Files.readString(FILE));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Map<String, Object> section = (Map<String, Object>) doc.get(side);
    List<String> entries = (List<String>) section.get(kind);
    return entries == null ? List.of() : List.copyOf(entries);
  }
}
