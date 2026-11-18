package mfs.mw.adapter.telco.partner.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import mfs.mw.adapter.telco.partner.CredentialRef;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.ToDefinition;

/**
 * The loader's rules for batch partner routes (ticket 86), beside {@link PartnerRouteLoader}: a
 * route {@code from: kamelet:mw-sftp-source?...} whose last step is {@code kamelet:mw-recon}, and
 * the {@code mw-sftp-sink} Kamelet. These three Kamelets take a short list of parameters, each held
 * to a pattern; anything else (an SFTP option such as {@code delete}, {@code
 * strictHostKeyChecking}, {@code privateKeyFile}) is refused. The credential ({@code secretField},
 * a password or with {@code auth=key} a private key) and the host's known hosts ({@code
 * knownHostsField}) are fields of the partner's own OpenBao secret: the loader reads them and the
 * Kamelet gets only registry references ({@code #bean:mw-sftp-<routeId>-<field>}, bound by {@link
 * PartnerRoutes}), never a value in a URI. Every host is checked against the record's allowed
 * hosts by the loader.
 */
final class PartnerBatchRoutes {

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
  private static final Set<String> DELIMITERS = Set.of("comma", "semicolon", "pipe", "tab");

  private static final Set<String> SFTP_KEYS = Set.of("host", "port", "username", "directory", "auth", "secretField", "knownHostsField");
  private static final Set<String> SOURCE_KEYS = Set.of("delay", "maxMessagesPerPoll");
  private static final Set<String> SINK_KEYS = Set.of("fileName");
  private static final Set<String> RECON_KEYS = Set.of("columns", "delimiter");

  private PartnerBatchRoutes() {}

  /** What the SFTP and recon Kamelets of one route name. */
  static final class Calls {
    /** Every SFTP host, checked against the record's allowed hosts. */
    final List<String> hosts = new ArrayList<>();
    /** Registry bean name -> secret field of the partner. */
    final Map<String, String> beans = new LinkedHashMap<>();
    String partnerId;
    String routeId;
    boolean batch;
    Set<String> kamelets;
  }

  /** True for {@code kamelet:mw-sftp-source} with or without parameters. */
  static boolean isBatchUri(Object uri) {
    return uri instanceof String s && (s.equals("kamelet:" + SOURCE) || s.startsWith("kamelet:" + SOURCE + "?"));
  }

  /** True when {@code uri} calls one of the batch Kamelets. */
  static boolean handles(String uri) {
    String name = name(uri);
    return SOURCE.equals(name) || SINK.equals(name) || RECON.equals(name);
  }

  private static String name(String uri) {
    int q = uri.indexOf('?');
    return uri.substring("kamelet:".length(), q < 0 ? uri.length() : q);
  }

  /**
   * The checked call {@code uri} with the loader's parameters, rebuilt from the accepted ones;
   * null (and a security problem) when it is refused. {@code from}: the start of the route.
   */
  static String kamelet(String uri, boolean from, Calls calls, List<String> security) {
    String name = name(uri);
    if (SOURCE.equals(name) != from) {
      security.add(SOURCE.equals(name)
          ? SOURCE + " may only start a batch route (from:), never be called"
          : "a batch route starts from " + SOURCE);
      return null;
    }
    if (RECON.equals(name) && !calls.batch) {
      security.add(RECON + " ends a batch route only");
      return null;
    }
    Set<String> keys = RECON.equals(name) ? RECON_KEYS : union(SFTP_KEYS, SOURCE.equals(name) ? SOURCE_KEYS : SINK_KEYS);
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
          security.add(name + " parameter " + key + " is not allowed (allowed: " + String.join(", ", new java.util.TreeSet<>(keys)) + ")");
          return null;
        }
        if (params.put(key, value) != null) {
          security.add(name + " parameter " + key + " is set twice");
          return null;
        }
      }
    }
    String problem = RECON.equals(name) ? reconProblem(params) : sftpProblem(name, params);
    if (problem != null) {
      security.add(name + " " + problem);
      return null;
    }
    calls.kamelets.add(name);
    StringBuilder out = new StringBuilder("kamelet:").append(name).append("?partnerId=").append(calls.partnerId);
    if (RECON.equals(name)) {
      params.forEach((k, v) -> out.append('&').append(k).append('=').append(v));
      return out.toString();
    }
    calls.hosts.add(params.get("host"));
    for (String key : List.of("host", "port", "username", "directory", "delay", "maxMessagesPerPoll", "fileName")) {
      if (params.containsKey(key)) {
        out.append('&').append(key).append('=').append(params.get(key));
      }
    }
    String credential = "key".equals(params.get("auth")) ? "privateKey" : "password";
    out.append('&').append(credential).append("=#bean:").append(bean(calls, params.get("secretField")));
    out.append("&knownHosts=#bean:").append(bean(calls, params.get("knownHostsField")));
    return out.toString();
  }

  private static String bean(Calls calls, String field) {
    String bean = "mw-sftp-" + calls.routeId + "-" + field;
    calls.beans.put(bean, field);
    return bean;
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
      if (!CredentialRef.NAME.matcher(p.get(field)).matches()) {
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

  /** A batch route ends with {@code mw-recon}. */
  static void ending(List<ProcessorDefinition<?>> outputs, List<String> problems) {
    if (outputs.isEmpty() || !(outputs.getLast() instanceof ToDefinition to) || to.getUri() == null
        || !(to.getUri().equals("kamelet:" + RECON) || to.getUri().startsWith("kamelet:" + RECON + "?"))) {
      problems.add("a batch route must end with mw-recon");
    }
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    Set<String> out = new java.util.HashSet<>(a);
    out.addAll(b);
    return out;
  }
}
