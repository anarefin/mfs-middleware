package mfs.mw.orchestrator.contracts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import tools.jackson.databind.json.JsonMapper;

/**
 * This module's {@code contracts.yml} (ticket 53): the contracts it produces and consumes, paths
 * relative to {@code contracts/}, {@code #<Msg>} naming one message type of a multi-type topic.
 * The test JVM runs in the module folder, so the manifest is {@code contracts.yml}.
 */
public final class ContractManifest {

  public static final Path FILE = Path.of("contracts.yml");

  private ContractManifest() {}

  /** The entries of {@code side} ({@code produces} / {@code consumes}) and {@code kind} ({@code events} / {@code apis}). */
  @SuppressWarnings("unchecked")
  public static List<String> entries(String side, String kind) {
    Object doc;
    try {
      doc = new Load(LoadSettings.builder().build()).loadFromString(Files.readString(FILE));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + FILE.toAbsolutePath(), e);
    }
    Map<String, Object> block = (Map<String, Object>) ((Map<String, Object>) doc).get(side);
    Object list = block == null ? null : block.get(kind);
    return list == null ? List.of() : List.copyOf((List<String>) list);
  }

  /** The schema path of an entry (before {@code #}). */
  public static String path(String entry) {
    int hash = entry.indexOf('#');
    return hash < 0 ? entry : entry.substring(0, hash);
  }

  /** The message type of an entry (after {@code #}), or null for a single-type topic. */
  public static String message(String entry) {
    int hash = entry.indexOf('#');
    return hash < 0 ? null : entry.substring(hash + 1);
  }

  /**
   * The JsonMapper the Spring context gives this service (Spring Boot 4 {@code
   * JacksonAutoConfiguration} without {@code spring.jackson.*} settings: Jackson 3 defaults plus
   * {@code findAndAddModules}; the ProblemDetail mix-in does not touch our types). The outbox, the
   * relay, the listeners and Spring MVC all use that bean.
   */
  public static JsonMapper serviceJsonMapper() {
    return JsonMapper.builder().findAndAddModules(ContractManifest.class.getClassLoader()).build();
  }
}
