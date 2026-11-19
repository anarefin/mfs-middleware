package mfs.mw.simulators.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import mfs.mw.simulators.dto.Biller;

/**
 * The simulators' versioned seed files (ticket 103, FRS MCK-08) on the classpath under {@code
 * seed/<version>/}, as mw-core-mock keeps its own: {@code billers.csv} (the bill aggregator's billers).
 * Plain CSV: a header line, {@code #} comments, no quoting.
 */
public final class SeedFiles {

  /** The seed version the simulators ship. */
  public static final String VERSION = "v1";

  private SeedFiles() {}

  public static List<Biller> billers(String version) {
    List<Biller> out = new ArrayList<>();
    for (String[] c : read(version, "billers.csv")) {
      out.add(new Biller(c[0], c[1], c[2], Biller.Protocol.valueOf(c[3])));
    }
    return List.copyOf(out);
  }

  private static List<String[]> read(String version, String file) {
    String path = "seed/" + version + "/" + file;
    InputStream in = SeedFiles.class.getClassLoader().getResourceAsStream(path);
    if (in == null) {
      throw new IllegalStateException("seed file missing: " + path);
    }
    List<String[]> rows = new ArrayList<>();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      boolean header = true;
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        if (header) {
          header = false;
          continue;
        }
        String[] cells = trimmed.split(",", -1);
        for (int i = 0; i < cells.length; i++) {
          cells[i] = cells[i].strip();
        }
        rows.add(cells);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return rows;
  }
}
