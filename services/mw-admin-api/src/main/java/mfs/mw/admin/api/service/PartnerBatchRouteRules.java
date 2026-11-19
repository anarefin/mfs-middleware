package mfs.mw.admin.api.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.scheduling.support.CronExpression;

/**
 * The maker-side copy (D14) of the adapters' {@code partner/route/PartnerBatchRoutes} (ticket 86,
 * review of 86): the short parameter lists of the {@code mw-sftp-source}, {@code mw-sftp-sink} and
 * {@code mw-recon} Kamelets ({@link Kind}), each value read URL-decoded (as Camel and the adapters'
 * {@code QueryParams} read it) and held to its pattern, and every SFTP host in the record's allowed
 * hosts. The source polls every {@code delay} ms (100 or more) or on a {@code cron} schedule, never
 * both; the sink may build its text with a JSONata {@code mapping} in a request route only. What
 * only the adapter can check (internal addresses, the secrets in Vault, that {@code mw-recon}
 * sits in a batch route only) is left to it.
 */
final class PartnerBatchRouteRules {

  static final String SOURCE = "mw-sftp-source";
  static final String SINK = "mw-sftp-sink";
  static final String RECON = "mw-recon";

  private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}))*$");
  private static final Pattern PORT = Pattern.compile("^[1-9][0-9]{0,4}$");
  private static final Pattern USER = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final Pattern DIRECTORY = Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*$");
  private static final Pattern DELAY = Pattern.compile("^[1-9][0-9]{2,7}$");
  private static final Pattern CRON = Pattern.compile("^[0-9A-Za-z*/,?#-]{1,32}( [0-9A-Za-z*/,?#-]{1,32}){5}$");
  private static final Pattern FILE_NAME = Pattern.compile("^([A-Za-z0-9._-]|\\$\\{date:now:[A-Za-z]{1,16}})+$");
  private static final Pattern COLUMNS = Pattern.compile("^([A-Za-z][A-Za-z0-9_]{0,63}(,[A-Za-z][A-Za-z0-9_]{0,63}){0,49})?$");
  private static final Pattern MAPPING = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
  private static final Pattern SECRET_FIELD = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
  private static final Set<String> DELIMITERS = Set.of("comma", "semicolon", "pipe", "tab");

  private static final Set<String> SFTP_KEYS = Set.of("host", "port", "username", "directory", "auth", "secretField", "knownHostsField");

  /** The batch Kamelets and the parameters a route may give each. */
  enum Kind {
    SOURCE(PartnerBatchRouteRules.SOURCE, union(SFTP_KEYS, Set.of("delay", "cron", "maxMessagesPerPoll"))),
    SINK(PartnerBatchRouteRules.SINK, union(SFTP_KEYS, Set.of("fileName", "mapping"))),
    RECON(PartnerBatchRouteRules.RECON, Set.of("columns", "delimiter"));

    final String kamelet;
    final Set<String> keys;

    Kind(String kamelet, Set<String> keys) {
      this.kamelet = kamelet;
      this.keys = keys;
    }

    static Kind of(String uri) {
      String name = kameletName(uri);
      for (Kind k : values()) {
        if (k.kamelet.equals(name)) {
          return k;
        }
      }
      return null;
    }
  }

  private PartnerBatchRouteRules() {}

  static boolean isBatchUri(String uri) {
    return uri != null && (uri.equals("kamelet:" + SOURCE) || uri.startsWith("kamelet:" + SOURCE + "?"));
  }

  static boolean handles(String uri) {
    return Kind.of(uri) != null;
  }

  private static String kameletName(String uri) {
    int q = uri.indexOf('?');
    return uri.substring("kamelet:".length(), q < 0 ? uri.length() : q);
  }

  /**
   * Every problem of the call {@code uri} ({@code from}: the start of the route; {@code batch}: in
   * a batch route); empty when accepted. A sink's {@code mapping} is added to {@code named}.
   */
  static List<String> problems(String uri, boolean from, boolean batch, List<String> allowedHosts, Set<String> named) {
    Kind kind = Kind.of(uri);
    if ((kind == Kind.SOURCE) != from) {
      return List.of(kind == Kind.SOURCE
          ? SOURCE + " may only start a batch route (from:), never be called"
          : "a batch route starts from " + SOURCE);
    }
    Map<String, String> params = new LinkedHashMap<>();
    int q = uri.indexOf('?');
    if (q >= 0) {
      for (String pair : uri.substring(q + 1).split("&")) {
        if (pair.isEmpty()) {
          continue;
        }
        int eq = pair.indexOf('=');
        String key;
        String value;
        try {
          key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
          value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
          return List.of(kind.kamelet + " parameters are not valid URI encoding");
        }
        if (!kind.keys.contains(key)) {
          return List.of(kind.kamelet + " parameter " + key + " is not allowed (allowed: "
              + String.join(", ", new TreeSet<>(kind.keys)) + ")");
        }
        if (params.put(key, value) != null) {
          return List.of(kind.kamelet + " parameter " + key + " is set twice");
        }
      }
    }
    String problem = kind == Kind.RECON ? reconProblem(params) : sftpProblem(kind, params, batch);
    if (problem != null) {
      return List.of(kind.kamelet + " " + problem);
    }
    if (params.containsKey("mapping")) {
      named.add(params.get("mapping"));
    }
    List<String> out = new ArrayList<>();
    if (kind != Kind.RECON && (allowedHosts == null || allowedHosts.stream().noneMatch(h -> h.equalsIgnoreCase(params.get("host"))))) {
      out.add(kind.kamelet + " host is not in the record's allowedHosts");
    }
    return out;
  }

  private static String sftpProblem(Kind kind, Map<String, String> p, boolean batch) {
    for (String required : List.of("host", "username", "directory", "secretField", "knownHostsField")) {
      if (!p.containsKey(required)) {
        return "needs " + required;
      }
    }
    if (kind == Kind.SINK && !p.containsKey("fileName")) {
      return "needs fileName";
    }
    if (!HOST.matcher(p.get("host")).matches()) {
      return "host must be a host name or IPv4 address";
    }
    if (p.containsKey("port") && (!PORT.matcher(p.get("port")).matches() || Integer.parseInt(p.get("port")) > 65535)) {
      return "port must be 1 to 65535";
    }
    if (!USER.matcher(p.get("username")).matches()) {
      return "username must be letters, digits, . _ -";
    }
    String dir = p.get("directory");
    if (!DIRECTORY.matcher(dir).matches() || java.util.Arrays.stream(dir.split("/")).anyMatch(s -> s.equals("..") || s.equals("."))) {
      return "directory must be a relative path of letters, digits, . _ - without . or .. segments";
    }
    if (p.containsKey("auth") && !Set.of("password", "key").contains(p.get("auth"))) {
      return "auth must be password or key";
    }
    for (String field : List.of("secretField", "knownHostsField")) {
      if (!SECRET_FIELD.matcher(p.get(field)).matches()) {
        return field + " must be a secret field of this partner (lower-case letters, digits, hyphen)";
      }
    }
    if (p.containsKey("delay") && !DELAY.matcher(p.get("delay")).matches()) {
      return "delay must be 100 to 99999999 ms";
    }
    if (p.containsKey("cron")) {
      if (p.containsKey("delay")) {
        return "polls with delay or cron, not both";
      }
      if (!CRON.matcher(p.get("cron")).matches() || !CronExpression.isValidExpression(p.get("cron"))) {
        return "cron must be a Spring cron expression of six fields (second minute hour day month weekday), + for a space";
      }
    }
    if (p.containsKey("maxMessagesPerPoll")) {
      String max = p.get("maxMessagesPerPoll");
      if (!max.matches("^[1-9][0-9]{0,2}$") || Integer.parseInt(max) > 100) {
        return "maxMessagesPerPoll must be 1 to 100";
      }
    }
    if (p.containsKey("fileName") && !FILE_NAME.matcher(p.get("fileName")).matches()) {
      return "fileName must be letters, digits, . _ - and ${date:now:<pattern>} tokens";
    }
    if (p.containsKey("mapping")) {
      if (!MAPPING.matcher(p.get("mapping")).matches()) {
        return "mapping must name one of the record's mappings (letters, digits, . _ -)";
      }
      if (batch) {
        return "mapping needs a JSON body: only a request route may map the file content";
      }
    }
    return null;
  }

  private static String reconProblem(Map<String, String> p) {
    if (!COLUMNS.matcher(p.getOrDefault("columns", "")).matches()) {
      return "columns must be names (letters, digits, underscore) separated by commas";
    }
    if (p.containsKey("delimiter") && !DELIMITERS.contains(p.get("delimiter"))) {
      return "delimiter must be comma, semicolon, pipe or tab";
    }
    return null;
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    Set<String> out = new java.util.HashSet<>(a);
    out.addAll(b);
    return Set.copyOf(out);
  }
}
