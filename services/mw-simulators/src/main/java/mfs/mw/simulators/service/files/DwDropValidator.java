package mfs.mw.simulators.service.files;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import mfs.mw.simulators.util.Hashing;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Data Warehouse's check of one daily drop (ticket 123, FRS EXT-17), pure. The control file {@code
 * control_<date>.json} is {@code {date, files:[{name, rows, sha256}]}}; every listed file must be in the drop
 * folder, carry that SHA-256, that many data rows (lines after the header, blank lines ignored) and the header of
 * its type (the name before {@code _<date>.csv}, schemas from {@code seed/<version>/dw/schemas.json}). Reasons, one
 * per problem: {@code BAD_CONTROL}, {@code MISSING_FILE <name>}, {@code CHECKSUM <name>}, {@code UNKNOWN_TYPE
 * <name>}, {@code SCHEMA <name>: header ..., expected ...}, {@code ROW_COUNT <name>: control n, file m}.
 */
public class DwDropValidator {

  /**
   * @param status ACCEPTED or REJECTED
   * @param files per listed file: OK or its first problem
   */
  public record Verdict(String status, List<String> reasons, Map<String, String> files) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Map<String, List<String>> schemas;

  public DwDropValidator(Map<String, List<String>> schemas) {
    this.schemas = schemas;
  }

  /** Checks the drop; {@code reader} answers a listed file's bytes, null when it is not there. */
  public Verdict validate(byte[] control, Function<String, byte[]> reader) {
    JsonNode c;
    try {
      c = JSON.readTree(control);
    } catch (RuntimeException e) {
      return new Verdict("REJECTED", List.of("BAD_CONTROL"), Map.of());
    }
    if (c == null || !c.path("files").isArray()) {
      return new Verdict("REJECTED", List.of("BAD_CONTROL"), Map.of());
    }
    List<String> reasons = new ArrayList<>();
    Map<String, String> files = new LinkedHashMap<>();
    for (JsonNode f : c.path("files")) {
      String name = f.path("name").asString("");
      String problem = check(name, f.path("rows").asLong(-1), f.path("sha256").asString(""), reader.apply(name));
      files.put(name, problem == null ? "OK" : problem);
      if (problem != null) {
        reasons.add(problem);
      }
    }
    return new Verdict(reasons.isEmpty() ? "ACCEPTED" : "REJECTED", List.copyOf(reasons), files);
  }

  private String check(String name, long rows, String sha, byte[] content) {
    if (content == null) {
      return "MISSING_FILE " + name;
    }
    if (!Hashing.sha256Hex(content).equalsIgnoreCase(sha)) {
      return "CHECKSUM " + name;
    }
    int cut = name.lastIndexOf('_');
    List<String> expected = cut > 0 ? schemas.get(name.substring(0, cut)) : null;
    if (expected == null) {
      return "UNKNOWN_TYPE " + name;
    }
    List<String> lines = new String(content, StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
    String header = lines.isEmpty() ? "" : lines.get(0).strip();
    if (!header.equals(String.join(",", expected))) {
      return "SCHEMA " + name + ": header " + header + ", expected " + String.join(",", expected);
    }
    long found = Math.max(0, lines.size() - 1);
    if (found != rows) {
      return "ROW_COUNT " + name + ": control " + rows + ", file " + found;
    }
    return null;
  }
}
