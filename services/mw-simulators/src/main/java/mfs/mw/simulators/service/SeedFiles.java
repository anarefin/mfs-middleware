package mfs.mw.simulators.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.simulators.dto.BankAccountSeed;
import mfs.mw.simulators.dto.BankProfile;
import mfs.mw.simulators.dto.Biller;
import mfs.mw.simulators.service.files.AmlResponder.AmlRules;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

  /**
   * The banks of the simulated bank gateway (ticket 117d, {@code bank-profiles.csv}, generated from {@code
   * deploy/partner-routes/bank/bank-profiles.csv} by its {@code generate.mjs}).
   */
  public static List<BankProfile> bankProfiles(String version) {
    List<BankProfile> out = new ArrayList<>();
    for (String[] c : read(version, "bank-profiles.csv")) {
      out.add(new BankProfile(c[0], c[1], BankProfile.Protocol.valueOf(c[2]), c[3]));
    }
    return List.copyOf(out);
  }

  /** The seeded accounts of the simulated bank gateway (ticket 117d, {@code bank-accounts.csv}). */
  public static List<BankAccountSeed> bankAccounts(String version) {
    List<BankAccountSeed> out = new ArrayList<>();
    for (String[] c : read(version, "bank-accounts.csv")) {
      out.add(new BankAccountSeed(c[0], c[1], c[2], c[3], new BigDecimal(c[4])));
    }
    return List.copyOf(out);
  }

  /** The AML vendor mock's rules (ticket 123, {@code aml/rules.json}): threshold, watch-list, block suffix. */
  public static AmlRules amlRules(String version) {
    JsonNode n = json(version, "aml/rules.json");
    List<String> watch = new ArrayList<>();
    n.path("watchList").forEach(w -> watch.add(w.asString()));
    return new AmlRules(n.path("alertAboveMinor").asLong(), List.copyOf(watch), n.path("blockSuffix").asString());
  }

  /** The Data Warehouse drop mock's file types and their CSV headers (ticket 123, {@code dw/schemas.json}). */
  public static Map<String, List<String>> dwSchemas(String version) {
    JsonNode n = json(version, "dw/schemas.json");
    Map<String, List<String>> out = new LinkedHashMap<>();
    n.properties().forEach(e -> {
      if (e.getValue().isArray()) {
        List<String> cols = new ArrayList<>();
        e.getValue().forEach(c -> cols.add(c.asString()));
        out.put(e.getKey(), List.copyOf(cols));
      }
    });
    return Map.copyOf(out);
  }

  private static JsonNode json(String version, String file) {
    String path = "seed/" + version + "/" + file;
    try (InputStream in = SeedFiles.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("seed file missing: " + path);
      }
      return JsonMapper.builder().build().readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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
