package mfs.mw.adapter.telco.partner.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import mfs.mw.adapter.telco.partner.CredentialRef;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.ToDefinition;
import org.springframework.scheduling.support.CronExpression;

/**
 * The loader's rules for batch partner routes (ticket 86), beside {@link PartnerRouteLoader}: a
 * route {@code from: kamelet:mw-sftp-source?...} whose last step is {@code kamelet:mw-recon}, and
 * the {@code mw-sftp-sink} Kamelet. These three Kamelets take a short list of parameters ({@link
 * Kind}), each read URL-decoded ({@link QueryParams}, as Camel reads it) and held to a pattern;
 * anything else (an SFTP option such as {@code delete}, {@code strictHostKeyChecking}, {@code
 * privateKeyFile}, {@code scheduler}) is refused, and the call is rebuilt from the accepted values
 * only. The credential ({@code secretField}, a password or with {@code auth=key} a private key) and
 * the host's known hosts ({@code knownHostsField}) are fields of the partner's own OpenBao secret:
 * the loader reads them and the Kamelet gets only registry references ({@code
 * #bean:mw-sftp-<routeId>-<field>}, bound by {@link PartnerRoutes}), never a value in a URI. Every
 * host is checked against the record's allowed hosts by the loader.
 *
 * <p>The source polls every {@code delay} ms (100 ms or more) or on a {@code cron} schedule (Spring
 * cron, six fields, {@code +} for a space; the loader then sets {@code scheduler=spring}), never
 * both. The sink writes the body as it is, or with {@code mapping} the text a JSONata mapping of the
 * record makes of the JSON body (CSV or fixed-width lines; request routes only, a batch route has no
 * JSON body); in a dry-run candidate the loader sets the sink's {@code dryRun}, so the text is made
 * but no file is put. {@code bindy} is not offered: it needs a Java model class per file layout, which a
 * route cannot ship and an allowlist cannot check.
 */
final class PartnerBatchRoutes {

  static final String SOURCE = "mw-sftp-source";
  static final String SINK = "mw-sftp-sink";
  static final String RECON = "mw-recon";

  private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}))*$");
  private static final Pattern PORT = Pattern.compile("^[1-9][0-9]{0,4}$");
  private static final Pattern USER = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final Pattern DIRECTORY = Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*$");
  private static final Pattern DELAY = Pattern.compile("^[1-9][0-9]{2,7}$");
  /** Six Spring cron fields of digits, letters (MON, JAN) and {@code * / , ? - L W #}, one space apart. */
  private static final Pattern CRON = Pattern.compile("^[0-9A-Za-z*/,?#-]{1,32}( [0-9A-Za-z*/,?#-]{1,32}){5}$");
  private static final Pattern FILE_NAME = Pattern.compile("^([A-Za-z0-9._-]|\\$\\{date:now:[A-Za-z]{1,16}})+$");
  private static final Pattern COLUMNS = Pattern.compile("^([A-Za-z][A-Za-z0-9_]{0,63}(,[A-Za-z][A-Za-z0-9_]{0,63}){0,49})?$");
  private static final Pattern MAPPING = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
  private static final Set<String> DELIMITERS = Set.of("comma", "semicolon", "pipe", "tab");

  private static final Set<String> SFTP_KEYS = Set.of("host", "port", "username", "directory", "auth", "secretField", "knownHostsField");

  /** The batch Kamelets and the parameters a route may give each. */
  enum Kind {
    SOURCE(PartnerBatchRoutes.SOURCE, union(SFTP_KEYS, Set.of("delay", "cron", "maxMessagesPerPoll"))),
    SINK(PartnerBatchRoutes.SINK, union(SFTP_KEYS, Set.of("fileName", "mapping"))),
    RECON(PartnerBatchRoutes.RECON, Set.of("columns", "delimiter"));

    final String kamelet;
    final Set<String> keys;

    Kind(String kamelet, Set<String> keys) {
      this.kamelet = kamelet;
      this.keys = keys;
    }

    boolean sftp() {
      return this != RECON;
    }

    /** The kind {@code uri} calls; null when it calls none of them. */
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

  private PartnerBatchRoutes() {}

  /**
   * One route's batch calls: what the loader knows of the route ({@code partnerId}, {@code
   * routeId}, whether it is a batch route) and what the checks collect (every SFTP host, the
   * registry beans of the secret fields, the Kamelets and the mappings named).
   */
  static final class Calls {
    final String partnerId;
    final String routeId;
    final boolean batch;
    /** A dry-run candidate: its sink writes nothing to the partner's host. */
    final boolean candidate;
    final Set<String> kamelets;
    final Set<String> mappings;
    /** Every SFTP host, checked against the record's allowed hosts. */
    final List<String> hosts = new ArrayList<>();
    /** Registry bean name -> secret field of the partner. */
    final Map<String, String> beans = new LinkedHashMap<>();

    Calls(String partnerId, String routeId, boolean batch, boolean candidate, Set<String> kamelets, Set<String> mappings) {
      this.partnerId = partnerId;
      this.routeId = routeId;
      this.batch = batch;
      this.candidate = candidate;
      this.kamelets = kamelets;
      this.mappings = mappings;
    }

    Calls(String partnerId, String routeId, boolean batch, Set<String> kamelets) {
      this(partnerId, routeId, batch, false, kamelets, new java.util.LinkedHashSet<>());
    }
  }

  /** True for {@code kamelet:mw-sftp-source} with or without parameters. */
  static boolean isBatchUri(Object uri) {
    return uri instanceof String s && (s.equals("kamelet:" + SOURCE) || s.startsWith("kamelet:" + SOURCE + "?"));
  }

  /** True when {@code uri} calls one of the batch Kamelets. */
  static boolean handles(String uri) {
    return Kind.of(uri) != null;
  }

  private static String kameletName(String uri) {
    int q = uri.indexOf('?');
    return uri.substring("kamelet:".length(), q < 0 ? uri.length() : q);
  }

  /**
   * The checked call {@code uri} with the loader's parameters, rebuilt from the accepted ones;
   * null (and a security problem) when it is refused. {@code from}: the start of the route.
   */
  static String kamelet(String uri, boolean from, Calls calls, List<String> security) {
    Kind kind = Kind.of(uri);
    if ((kind == Kind.SOURCE) != from) {
      security.add(kind == Kind.SOURCE
          ? SOURCE + " may only start a batch route (from:), never be called"
          : "a batch route starts from " + SOURCE);
      return null;
    }
    if (kind == Kind.RECON && !calls.batch) {
      security.add(RECON + " ends a batch route only");
      return null;
    }
    Map<String, String> params = new LinkedHashMap<>();
    int q = uri.indexOf('?');
    List<Map.Entry<String, String>> pairs;
    try {
      pairs = QueryParams.parse(q < 0 ? "" : uri.substring(q + 1));
    } catch (IllegalArgumentException e) {
      security.add(kind.kamelet + " parameters are not valid URI encoding");
      return null;
    }
    for (Map.Entry<String, String> pair : pairs) {
      if (!kind.keys.contains(pair.getKey())) {
        security.add(kind.kamelet + " parameter " + pair.getKey() + " is not allowed (allowed: "
            + String.join(", ", new TreeSet<>(kind.keys)) + ")");
        return null;
      }
      if (params.put(pair.getKey(), pair.getValue()) != null) {
        security.add(kind.kamelet + " parameter " + pair.getKey() + " is set twice");
        return null;
      }
    }
    String problem = kind == Kind.RECON ? reconProblem(params) : sftpProblem(kind, params, calls.batch);
    if (problem != null) {
      security.add(kind.kamelet + " " + problem);
      return null;
    }
    calls.kamelets.add(kind.kamelet);
    StringBuilder out = new StringBuilder("kamelet:").append(kind.kamelet).append("?partnerId=").append(calls.partnerId);
    if (!kind.sftp()) {
      params.forEach((k, v) -> out.append('&').append(k).append('=').append(v));
      return out.toString();
    }
    calls.hosts.add(params.get("host"));
    for (String key : List.of("host", "port", "username", "directory", "delay", "maxMessagesPerPoll", "fileName", "mapping")) {
      if (params.containsKey(key)) {
        out.append('&').append(key).append('=').append(params.get(key));
      }
    }
    if (params.containsKey("cron")) {
      out.append("&scheduler=spring&cron=").append(params.get("cron").replace(' ', '+'));
    }
    if (params.containsKey("mapping")) {
      calls.mappings.add(params.get("mapping"));
    }
    if (kind == Kind.SINK) {
      // a dry-run candidate runs the whole route but never puts a file on the partner's host
      out.append("&dryRun=").append(calls.candidate);
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
      if (!CredentialRef.NAME.matcher(p.get(field)).matches()) {
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
    return Set.copyOf(out);
  }
}
