package mfs.mw.seeder.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.dto.SeedManifest;
import mfs.mw.seeder.util.MaskingPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * write-manifest: the seeded ids and personas as JSON at {@code mw.seeder.manifest-path} (the
 * {@code seed-manifest} volume in the stack; {@code make seed} copies it to {@code
 * build/seed/manifest.json}), plus one masked log line. The file is replaced atomically and only
 * when its content changed, so a second run reports UNCHANGED.
 */
@Service
@Order(6)
public class WriteManifestStep implements SeedStep {

  private static final Logger log = LoggerFactory.getLogger(WriteManifestStep.class);

  private final Path file;
  private final JsonMapper json;

  public WriteManifestStep(SeederProperties props, JsonMapper json) {
    this.file = props.manifestPath();
    this.json = json.rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();
  }

  @Override
  public String id() {
    return "write-manifest";
  }

  @Override
  public StepResult run(SeedContext ctx) {
    SeedManifest manifest = ctx.manifest();
    String text = json.writeValueAsString(manifest) + "\n";
    StepResult result;
    try {
      boolean existed = Files.isRegularFile(file);
      if (existed && Files.readString(file, StandardCharsets.UTF_8).equals(text)) {
        result = StepResult.unchanged(1);
      } else {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".manifest-", ".json");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        result = existed ? StepResult.updated(1) : StepResult.created(1);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write the seed manifest " + file, e);
    }
    log.atInfo()
        .addKeyValue("event", "seed.manifest")
        .log("seed manifest at {}: {}", file, masked(manifest));
    return result;
  }

  /** The manifest as logged: MSISDNs masked (18.3); it holds no secret to begin with. */
  static String masked(SeedManifest manifest) {
    return MaskingPolicy.scrub(MaskingPolicy.describe(manifest));
  }
}
