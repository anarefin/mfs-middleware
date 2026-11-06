package mfs.mw.admin.api.contracts;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads contract schemas from the root {@code contracts/} folder, which reaches this module only
 * through the {@code mwContractsDir} Gradle property (spec 4.3 rule 2), in tests only.
 *
 * <p>Every schema has an absolute {@code $id} under {@code https://contracts.bits.mw/} that mirrors
 * its path, and refers to others with relative {@code $ref}s (envelope -> per-message payload ->
 * {@code common/defs.v1}). The registry reads every id under that prefix from {@code mwContractsDir}, so refs resolve
 * from disk and nothing is fetched over the network. See contracts/README.md.
 */
public final class ContractSchemas {

  public static final String ID_BASE = "https://contracts.bits.mw/";

  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12,
          builder -> builder.schemas(ContractSchemas::read));

  /** Schema text for a contracts id, read from mwContractsDir; null for any other id. */
  private static String read(String id) {
    if (!id.startsWith(ID_BASE)) {
      return null;
    }
    try {
      return Files.readString(fileFor(id));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read contract " + id, e);
    }
  }

  private ContractSchemas() {}

  /** The root contracts/ folder, from the mwContractsDir system property set by the build. */
  public static Path contractsDir() {
    String dir = System.getProperty("mwContractsDir");
    if (dir == null || dir.isBlank()) {
      throw new IllegalStateException("mwContractsDir system property is not set (build.gradle.kts)");
    }
    return Path.of(dir).toAbsolutePath().normalize();
  }

  /** The schema at {@code path} under contracts/, e.g. events/mw-x/topic.v1.schema.json. */
  public static Schema schema(String path) {
    return REGISTRY.getSchema(SchemaLocation.of(ID_BASE + path));
  }

  /** The file a contracts {@code $id} maps to. */
  public static Path fileFor(String id) {
    if (!id.startsWith(ID_BASE)) {
      throw new IllegalArgumentException("not a contracts id: " + id);
    }
    return contractsDir().resolve(id.substring(ID_BASE.length()));
  }

  /** Sample files in {@code dir} under contracts/ whose names start with {@code prefix}, sorted. */
  public static List<Path> samples(String dir, String prefix) {
    try (Stream<Path> files = Files.list(contractsDir().resolve(dir))) {
      return files
          .filter(p -> p.getFileName().toString().startsWith(prefix))
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
