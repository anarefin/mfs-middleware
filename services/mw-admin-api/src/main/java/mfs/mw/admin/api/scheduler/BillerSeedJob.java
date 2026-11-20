package mfs.mw.admin.api.scheduler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.config.BillerSeedProperties;
import mfs.mw.admin.api.service.BillerCatalogService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Stores the biller seed catalogue ({@link BillerSeedProperties}, dev profile only) at start-up:
 * every code not in the catalogue yet is stored at version 1 and published; an existing code is
 * never touched, so a re-run stores nothing (ticket 113). A broken seed fails start-up.
 *
 * <p>CSV columns (header line first, {@code ;} separated, UTF-8): code, category, nameEn, nameBn,
 * primaryAggregator, fallbackAggregator (may be empty), amountRule, minAmountMinor,
 * maxAmountMinor (may be empty), billMonthRequired, then one input field: fieldName, labelEn,
 * labelBn, regex.
 */
@Component
@EnableConfigurationProperties(BillerSeedProperties.class)
public class BillerSeedJob implements ApplicationRunner {

  private final BillerSeedProperties props;
  private final BillerCatalogService catalog;
  private final ResourceLoader resources;

  public BillerSeedJob(BillerSeedProperties props, BillerCatalogService catalog, ResourceLoader resources) {
    this.props = props;
    this.catalog = catalog;
    this.resources = resources;
  }

  @Override
  public void run(ApplicationArguments args) {
    seedAll();
  }

  /** Stores every seed biller that is missing; returns how many were stored. */
  public int seedAll() {
    int stored = 0;
    for (String location : props.seeds()) {
      if (location == null || location.isBlank()) {
        continue;
      }
      for (Map<String, Object> value : read(location)) {
        if (catalog.seed(value)) {
          stored++;
        }
      }
    }
    return stored;
  }

  private List<Map<String, Object>> read(String location) {
    List<Map<String, Object>> out = new ArrayList<>();
    try (BufferedReader in =
        new BufferedReader(
            new InputStreamReader(resources.getResource(location).getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      boolean header = true;
      while ((line = in.readLine()) != null) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        if (header) {
          header = false;
          continue;
        }
        out.add(parse(line));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("biller seed " + location + " cannot be read", e);
    }
    return out;
  }

  static Map<String, Object> parse(String line) {
    String[] c = line.split(";", -1);
    if (c.length != 14) {
      throw new IllegalStateException("biller seed line has " + c.length + " columns, not 14");
    }
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("code", c[0].trim());
    v.put("category", c[1].trim());
    v.put("nameEn", c[2].trim());
    v.put("nameBn", c[3].trim());
    v.put("primaryAggregator", c[4].trim());
    if (!c[5].isBlank()) {
      v.put("fallbackAggregator", c[5].trim());
    }
    v.put("amountRule", c[6].trim());
    if (!c[7].isBlank()) {
      v.put("minAmountMinor", Long.parseLong(c[7].trim()));
    }
    if (!c[8].isBlank()) {
      v.put("maxAmountMinor", Long.parseLong(c[8].trim()));
    }
    v.put("billMonthRequired", Boolean.parseBoolean(c[9].trim()));
    Map<String, Object> field = new LinkedHashMap<>();
    field.put("name", c[10].trim());
    field.put("labelEn", c[11].trim());
    field.put("labelBn", c[12].trim());
    field.put("regex", c[13].trim());
    field.put("required", true);
    v.put("fields", List.of(field));
    v.put("enabled", true);
    return v;
  }
}
