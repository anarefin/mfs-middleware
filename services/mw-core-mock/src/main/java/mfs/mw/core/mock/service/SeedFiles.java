package mfs.mw.core.mock.service;

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
import java.util.function.Function;
import mfs.mw.core.mock.entity.LimitRule;
import mfs.mw.core.mock.entity.TariffRow;
import mfs.mw.core.mock.entity.Wallet;

/**
 * The versioned seed files (ticket 94, FRS 7.3) on the classpath under {@code seed/<version>/}:
 * {@code wallets.csv} (ranges of people's wallets), {@code system-wallets.csv}, {@code tariff.csv} and
 * {@code limits.csv}. Plain CSV: a header line, {@code #} comments, no quoting; an empty cell is null.
 */
public final class SeedFiles {

  /** One range of generated wallets: ids {@code prefix + %04d} for {@code first..last}. */
  public record WalletRange(String type, String prefix, int first, int last, String kycLevel, long openingBalanceMinor) {

    public String id(int n) {
      return prefix + String.format("%04d", n);
    }
  }

  /** One system wallet (no MSISDN) and its opening balance. */
  public record SystemWallet(String id, long openingBalanceMinor) {}

  private final String version;

  public SeedFiles(String version) {
    this.version = version;
  }

  public List<WalletRange> wallets() {
    return read("wallets.csv", c -> new WalletRange(Wallet.canonicalType(c[0]), c[1], Integer.parseInt(c[2]),
        Integer.parseInt(c[3]), c[4], Long.parseLong(c[5])));
  }

  public List<SystemWallet> systemWallets() {
    return read("system-wallets.csv", c -> new SystemWallet(c[0], Long.parseLong(c[1])));
  }

  public List<TariffRow> tariff() {
    return read("tariff.csv", c -> new TariffRow(c[0], c[1], Wallet.canonicalType(c[2]), Long.parseLong(c[3]),
        Long.parseLong(c[4]), Long.parseLong(c[5]), new BigDecimal(c[6]), split(cell(c, 7))));
  }

  public List<LimitRule> limits() {
    return read("limits.csv", c -> new LimitRule(c[0], Wallet.canonicalType(c[1]), c[2], longOrNull(cell(c, 3)),
        longOrNull(cell(c, 4)), intOrNull(cell(c, 5)), longOrNull(cell(c, 6)), intOrNull(cell(c, 7)),
        longOrNull(cell(c, 8))));
  }

  /** {@code AGENT:40|DISTRIBUTOR:10} -> {AGENT=40, DISTRIBUTOR=10}. */
  static Map<String, BigDecimal> split(String cell) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    if (cell == null) {
      return out;
    }
    for (String part : cell.split("\\|")) {
      String[] kv = part.split(":", 2);
      out.put(Wallet.canonicalType(kv[0].trim()), new BigDecimal(kv[1].trim()));
    }
    return out;
  }

  private <T> List<T> read(String file, Function<String[], T> row) {
    String path = "seed/" + version + "/" + file;
    InputStream in = SeedFiles.class.getClassLoader().getResourceAsStream(path);
    if (in == null) {
      throw new IllegalStateException("seed file missing: " + path);
    }
    List<T> rows = new ArrayList<>();
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
        rows.add(row.apply(cells));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return rows;
  }

  private static String cell(String[] c, int i) {
    return i < c.length && !c[i].isEmpty() ? c[i] : null;
  }

  private static Long longOrNull(String s) {
    return s == null ? null : Long.valueOf(s);
  }

  private static Integer intOrNull(String s) {
    return s == null ? null : Integer.valueOf(s);
  }
}
