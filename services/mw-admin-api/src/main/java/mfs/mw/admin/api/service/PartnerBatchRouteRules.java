package mfs.mw.admin.api.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The maker-side copy (D14) of the adapters' {@code partner/route/PartnerBatchRoutes} (ticket 86):
 * the short parameter lists of the {@code mw-sftp-source}, {@code mw-sftp-sink} and {@code
 * mw-recon} Kamelets, each value held to its pattern, and every SFTP host in the record's allowed
 * hosts. What only the adapter can check (internal addresses, the secrets in OpenBao, that {@code
 * mw-recon} sits in a batch route only) is left to it.
 */
final class PartnerBatchRouteRules {

  static final String SOURCE = "mw-sftp-source";
  static final String SINK = "mw-sftp-sink";
  static final String RECON = "mw-recon";

  private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}))*$");
  private static final Pattern PORT = Pattern.compile("^[1-9][0-9]{0,4}$");
  private static final Pattern USER = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final Pattern DIRECTORY = Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*$");
  private static final Pattern DELAY = Pattern.compile("^[1-9][0-9]{3,7}$");
  private static final Pattern FILE_NAME = Pattern.compile("^([A-Za-z0-9._-]|\\$\\{date:now:[A-Za-z]{1,16}})+$");
  private static final Pattern COLUMNS = Pattern.compile("^([A-Za-z][A-Za-z0-9_]{0,63}(,[A-Za-z][A-Za-z0-9_]{0,63}){0,49})?$");
  private static final Pattern SECRET_FIELD = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
  private static final Set<String> DELIMITERS = Set.of("comma", "semicolon", "pipe", "tab");

  private static final Set<String> SFTP_KEYS = Set.of("host", "port", "username", "directory", "auth", "secretField", "knownHostsField");
  private static final Set<String> SOURCE_KEYS = Set.of("delay", "maxMessagesPerPoll");
  private static final Set<String> SINK_KEYS = Set.of("fileName");
  private static final Set<String> RECON_KEYS = Set.of("columns", "delimiter");

  private PartnerBatchRouteRules() {}

  static boolean isBatchUri(String uri) {
    return uri != null && (uri.equals("kamelet:" + SOURCE) || uri.startsWith("kamelet:" + SOURCE + "?"));
  }

  static boolean handles(String uri) {
    String name = name(uri);
    return SOURCE.equals(name) || SINK.equals(name) || RECON.equals(name);
  }

  private static String name(String uri) {
    int q = uri.indexOf('?');
    return uri.substring("kamelet:".length(), q < 0 ? uri.length() : q);
  }

  /** Every problem of the call {@code uri} ({@code from}: the start of the route); empty when accepted. */
  static List<String> problems(String uri, boolean from, List<String> allowedHosts) {
    String name = name(uri);
    if (SOURCE.equals(name) != from) {
      return List.of(SOURCE.equals(name)
          ? SOURCE + " may only start a batch route (from:), never be called"
          : "a batch route starts from " + SOURCE);
    }
    Set<String> keys = new java.util.HashSet<>(RECON.equals(name) ? RECON_KEYS : SFTP_KEYS);
    if (!RECON.equals(name)) {
      keys.addAll(SOURCE.equals(name) ? SOURCE_KEYS : SINK_KEYS);
    }
    Map<String, String> params = new LinkedHashMap<>();
    int q = uri.indexOf('?');
    if (q >= 0) {
      for (String pair : uri.substring(q + 1).split("&")) {
        if (pair.isEmpty()) {
          continue;
        }
        int eq = pair.indexOf('=');
        String key = eq < 0 ? pair : pair.substring(0, eq);
        String value = eq < 0 ? "" : pair.substring(eq + 1);
        if (!keys.contains(key)) {
          return List.of(name + " parameter " + key + " is not allowed (allowed: " + String.join(", ", new TreeSet<>(keys)) + ")");
        }
        if (params.put(key, value) != null) {
          return List.of(name + " parameter " + key + " is set twice");
        }
      }
    }
    String problem = RECON.equals(name) ? reconProblem(params) : sftpProblem(name, params);
    if (problem != null) {
      return List.of(name + " " + problem);
    }
    List<String> out = new ArrayList<>();
    if (!RECON.equals(name) && (allowedHosts == null || allowedHosts.stream().noneMatch(h -> h.equalsIgnoreCase(params.get("host"))))) {
      out.add(name + " host is not in the record's allowedHosts");
    }
    return out;
  }

  private static String sftpProblem(String name, Map<String, String> p) {
    for (String required : List.of("host", "username", "directory", "secretField", "knownHostsField")) {
      if (!p.containsKey(required)) {
        return "needs " + required;
      }
    }
    if (SINK.equals(name) && !p.containsKey("fileName")) {
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
      return "delay must be 1000 to 99999999 ms";
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
}
