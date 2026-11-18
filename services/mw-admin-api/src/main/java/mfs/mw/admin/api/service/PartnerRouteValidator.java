package mfs.mw.admin.api.service;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.NodePath;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.admin.api.config.PartnerRouteProperties;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The maker-side check of a partner route (ADR D26 guardrail 2, ticket 83): a copy of the rules
 * of mw-adapter-billpay's {@code partner/route/PartnerRouteLoader} (kept identical under D14; the
 * adapters check every record again and stay the authority). This module has no Camel (ADR D1
 * amendment), so the loader's walk over Camel's parsed model is done here over the YAML tree with
 * the same allowlist; everything else is the loader's rule for rule, in its order:
 *
 * <ol>
 *   <li>no {@code {{...}}} anywhere (text and every parsed value);
 *   <li>safe YAML (no duplicate keys, no aliases): exactly one {@code - route:} holding only {@code
 *       from {uri: direct:partner-<id>, steps}}; no error-handling key anywhere;
 *   <li>Camel's YAML DSL JSON Schema ({@code schema/camelYamlDsl.json}, copied from camel-yaml-dsl
 *       4.22.2, the Camel of the adapters; draft 4);
 *   <li>Kamelet calls: {@code to: "kamelet:mw-*"} strings of the library ({@link
 *       PartnerRouteProperties#kamelets()}), static, parameter names {@code ^[A-Za-z][A-Za-z0-9]*$}
 *       each once, none the loader sets, no {@code RAW(...)}, {@code url}/{@code path}/... without
 *       {@code % ? & #}; {@code mw-http-call}: an auth with its {@code mw-auth-*} Kamelet and a
 *       secret field, a safe path, a URL whose host is in the record's allowed hosts;
 *   <li>the allowlist of steps: {@code choice}/{@code when}/{@code otherwise}, {@code to}, {@code
 *       setHeader} (not {@code Camel*}/{@code mw*}), {@code setBody}, {@code transform}, {@code log}
 *       (never the body), JSON {@code marshal}/{@code unmarshal}; expressions {@code constant} or
 *       {@code simple} reading only {@code ${body}}, {@code ${header.x}}, {@code ${variable.x}},
 *       {@code ${date:now}};
 *   <li>the route ends with {@code mw-classify} (directly, or at the end of every branch of a final
 *       {@code choice}) and then {@code mw-audit}; every mapping it names is in the record.
 * </ol>
 *
 * <p>Each problem carries the 1-based line of the YAML text and the construct. What only the
 * adapter can check (https or a dev host, internal addresses, the static host floor, JSONata
 * compilation, the secrets in OpenBao, Camel's own parse) is left to it: a version it refuses is
 * reported REJECTED.
 */
@Component
public class PartnerRouteValidator {

  /** One problem: 1-based line (0: the whole route), the construct, what is wrong. */
  public record Problem(int line, String construct, String message) {
    @Override
    public String toString() {
      return (line > 0 ? "line " + line + ": " : "") + construct + ": " + message;
    }
  }

  static final Set<String> STRICT_PARAMS = Set.of("url", "path", "authHeader", "keyId", "method", "auth", "secretField");
  static final Set<String> RESERVED_KAMELET_PARAMS =
      Set.of("partnerid", "timeoutms", "connecttimeoutms", "readtimeoutms", "bulkhead",
          "location", "templateid", "routeid", "uuid", "noerrorhandler", "bridgeerrorhandler");
  static final Set<String> FORBIDDEN_KEYS =
      Set.of("onException", "errorHandler", "intercept", "interceptFrom", "interceptSendToEndpoint",
          "onCompletion", "doTry", "doCatch", "doFinally", "routeConfiguration", "circuitBreaker", "onFallback");
  static final Set<String> STEPS = Set.of("choice", "to", "setHeader", "setBody", "transform", "log", "marshal", "unmarshal");
  static final Set<String> LANGUAGES = Set.of("constant", "simple");

  /**
   * The package-style prefix of Camel's internal header names, refused like {@code Camel*}. Spelt in
   * two parts on purpose: this module has no Camel (scripts/check-isolation.sh looks for the package
   * name), it only knows the header names.
   */
  static final String CAMEL_HEADER_PACKAGE = "org.apache." + "camel";

  /** {@code mw-soap-call} parameters held to the strict rule too (ticket 85). */
  static final Set<String> SOAP_STRICT_PARAMS =
      Set.of("address", "soapAction", "soapVersion", "request", "response", "wsUsername", "wsPasswordSecret", "wsTokenType");
  static final Pattern CLASSPATH_STYLESHEET = Pattern.compile("^classpath:partner/xslt/[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}\\.xsl$");

  private static final Pattern PARAM_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9]*$");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$(?:simple)?\\{([^}]*)}");
  private static final Pattern SIMPLE_TOKEN =
      Pattern.compile("^(body|header\\.[A-Za-z0-9_-]+|headers\\.[A-Za-z0-9_-]+|variable\\.[A-Za-z0-9_-]+|date:now(:[A-Za-z0-9 .:'_-]*)?)$");
  private static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9._~/{}-]*$");

  private final Set<String> kamelets;
  private final JsonMapper json;
  private volatile Schema yamlDslSchema;

  public PartnerRouteValidator(PartnerRouteProperties properties, JsonMapper json) {
    this.kamelets = Set.copyOf(properties.kamelets());
    this.json = json;
  }

  /** The Kamelet library a route may call. */
  public Set<String> kamelets() {
    return kamelets;
  }

  /**
   * Every problem of {@code route} as partner {@code id}'s route with {@code mappings} and {@code
   * allowedHosts}; empty when the maker side finds none.
   */
  public List<Problem> problems(String id, String route, Map<String, String> mappings, List<String> allowedHosts) {
    List<Problem> problems = new ArrayList<>();
    if (route == null || route.isBlank()) {
      problems.add(new Problem(0, "route", "is required"));
      return problems;
    }
    int braces = route.indexOf("{{");
    if (braces >= 0) {
      problems.add(new Problem(lineOf(route, braces), "property placeholder",
          "property placeholders ({{...}}) are not allowed in a partner route"));
      return problems;
    }
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setAllowRecursiveKeys(false);
    Node root;
    Object doc;
    try {
      Yaml yaml = new Yaml(new SafeConstructor(options));
      root = yaml.compose(new StringReader(route));
      doc = new Yaml(new SafeConstructor(options)).load(route);
    } catch (RuntimeException e) {
      problems.add(new Problem(0, "YAML", "the route is not valid YAML: " + firstLine(e.getMessage())));
      return problems;
    }
    SequenceNode steps = structure(id, root, allowedHosts, problems);
    if (!problems.isEmpty() || steps == null) {
      return problems;
    }
    placeholders(root, problems);
    if (!problems.isEmpty()) {
      return problems;
    }
    schema(root, doc, problems);
    if (!problems.isEmpty()) {
      return problems;
    }
    Set<String> named = new LinkedHashSet<>();
    walk(steps, allowedHosts, named, problems);
    if (isBatch(doc)) {
      batchEnding(steps, problems);
    } else {
      ending(steps, problems);
    }
    for (String name : named) {
      if (mappings == null || !mappings.containsKey(name)) {
        problems.add(new Problem(0, "mapping", "the route names mapping " + name + ", which the record does not have"));
      }
    }
    if (mappings != null) {
      // ticket 85: every *.xsl mapping is an XSLT stylesheet that may not reach outside itself
      mappings.forEach((name, source) -> {
        if (name.endsWith(".xsl")) {
          StylesheetPolicy.problems(source).forEach(p -> problems.add(new Problem(0, "stylesheet " + name, p)));
        }
      });
    }
    return problems;
  }

  // --- structure (loader step 3) ----------------------------------------------------------------

  private static SequenceNode structure(String id, Node root, List<String> allowedHosts, List<Problem> problems) {
    if (!(root instanceof SequenceNode list) || list.getValue().size() != 1
        || !(list.getValue().getFirst() instanceof MappingNode item) || item.getValue().size() != 1
        || !"route".equals(key(item.getValue().getFirst()))) {
      problems.add(new Problem(line(root), "top level",
          "a partner route is exactly one '- route:' and nothing else (no beans, routeConfiguration,"
              + " errorHandler, onException, intercept, rest, templates, sslContextParameters)"));
      return null;
    }
    Node routeNode = item.getValue().getFirst().getValueNode();
    if (!(routeNode instanceof MappingNode route) || route.getValue().size() != 1
        || !"from".equals(key(route.getValue().getFirst()))) {
      problems.add(new Problem(line(routeNode), "route",
          "the route may hold only 'from' (id, error handler, policies, message history, trace,"
              + " startup and the like are set by the loader or not at all)"));
      return null;
    }
    Node fromNode = route.getValue().getFirst().getValueNode();
    Map<String, Node> from = fromNode instanceof MappingNode m ? entries(m) : null;
    if (from == null || !Set.of("uri", "steps").containsAll(from.keySet()) || !(from.get("steps") instanceof SequenceNode)) {
      problems.add(new Problem(line(fromNode), "from", "'from' may hold only 'uri' and 'steps'"));
      return null;
    }
    Node forbidden = forbiddenKey(from.get("steps"));
    if (forbidden != null) {
      problems.add(new Problem(line(forbidden), "error handling",
          "a partner route may not declare onException, errorHandler, intercept*, onCompletion,"
              + " doTry, routeConfiguration or circuitBreaker; the loader owns error handling"));
    }
    String fromUri = scalar(from.get("uri"));
    Node uriNode = from.get("uri") == null ? fromNode : from.get("uri");
    if (PartnerBatchRouteRules.isBatchUri(fromUri)) {
      // ticket 86: a batch route starts from the mw-sftp-source Kamelet
      PartnerBatchRouteRules.problems(fromUri, true, allowedHosts)
          .forEach(p -> problems.add(new Problem(line(uriNode), "from", p)));
    } else if (!("direct:partner-" + id).equals(fromUri)) {
      problems.add(new Problem(line(uriNode), "from",
          "the route must start from direct:partner-" + id + " (or kamelet:mw-sftp-source for a batch route)"));
    }
    return (SequenceNode) from.get("steps");
  }

  private static Node forbiddenKey(Node node) {
    if (node instanceof MappingNode map) {
      for (NodeTuple t : map.getValue()) {
        if (FORBIDDEN_KEYS.contains(key(t))) {
          return t.getKeyNode();
        }
        Node inner = forbiddenKey(t.getValueNode());
        if (inner != null) {
          return inner;
        }
      }
    } else if (node instanceof SequenceNode list) {
      for (Node n : list.getValue()) {
        Node inner = forbiddenKey(n);
        if (inner != null) {
          return inner;
        }
      }
    }
    return null;
  }

  /** The parsed values: YAML escapes, quoting and block scalars can hide a placeholder. */
  private static void placeholders(Node node, List<Problem> problems) {
    if (node instanceof ScalarNode s) {
      if (s.getValue().contains("{{") || s.getValue().contains("}}")) {
        problems.add(new Problem(line(s), "property placeholder",
            "property placeholders ({{...}}) are not allowed in a partner route"));
      }
    } else if (node instanceof MappingNode map) {
      for (NodeTuple t : map.getValue()) {
        placeholders(t.getKeyNode(), problems);
        placeholders(t.getValueNode(), problems);
      }
    } else if (node instanceof SequenceNode list) {
      list.getValue().forEach(n -> placeholders(n, problems));
    }
  }

  // --- JSON Schema (loader step 4) --------------------------------------------------------------

  private void schema(Node root, Object doc, List<Problem> problems) {
    JsonNode tree = json.valueToTree(doc);
    List<Error> errors = yamlDslSchema().validate(tree);
    errors.stream().limit(5).forEach(e -> problems.add(
        new Problem(line(at(root, e.getInstanceLocation())), "YAML DSL schema",
            String.valueOf(e.getInstanceLocation()) + " " + e.getMessage())));
  }

  /** The node at {@code path}, or the deepest one on the way. */
  private static Node at(Node root, NodePath path) {
    Node node = root;
    if (path == null) {
      return node;
    }
    for (int i = 0; i < path.getNameCount(); i++) {
      Object element = path.getElement(i);
      Node next = null;
      if (node instanceof SequenceNode list && element instanceof Integer index && index < list.getValue().size()) {
        next = list.getValue().get(index);
      } else if (node instanceof MappingNode map) {
        for (NodeTuple t : map.getValue()) {
          if (String.valueOf(element).equals(key(t))) {
            next = t.getValueNode();
          }
        }
      }
      if (next == null) {
        return node;
      }
      node = next;
    }
    return node;
  }

  private Schema yamlDslSchema() {
    Schema schema = yamlDslSchema;
    if (schema == null) {
      try (InputStream in = getClass().getResourceAsStream("/schema/camelYamlDsl.json")) {
        if (in == null) {
          throw new IllegalStateException("camel-yaml-dsl schema not on the classpath");
        }
        schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_4).getSchema(in);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      yamlDslSchema = schema;
    }
    return schema;
  }

  // --- steps: Kamelet calls and the allowlist (loader steps 5 and 7) ----------------------------

  private void walk(SequenceNode steps, List<String> allowedHosts, Set<String> named, List<Problem> problems) {
    for (Node stepNode : steps.getValue()) {
      if (!(stepNode instanceof MappingNode step) || step.getValue().size() != 1) {
        problems.add(new Problem(line(stepNode), "step", "a step is one EIP name with its settings"));
        continue;
      }
      NodeTuple t = step.getValue().getFirst();
      String name = key(t);
      Node value = t.getValueNode();
      if (!STEPS.contains(name)) {
        problems.add(new Problem(line(t.getKeyNode()), name, "'" + name + "' is not allowed in a partner route"));
        continue;
      }
      switch (name) {
        case "to" -> kamelet(value, allowedHosts, named, problems);
        case "setHeader" -> {
          Map<String, Node> fields = value instanceof MappingNode m ? entries(m) : Map.of();
          String header = scalar(fields.get("name"));
          String lower = header == null ? "" : header.toLowerCase(Locale.ROOT);
          if (lower.startsWith("camel") || lower.startsWith(CAMEL_HEADER_PACKAGE) || lower.startsWith("mw")) {
            problems.add(new Problem(line(value), "setHeader", "setHeader may not set a Camel* or mw* header (" + header + ")"));
          }
          expression(value, Set.of("name"), problems, "setHeader");
        }
        case "setBody", "transform" -> expression(value, Set.of(), problems, name);
        case "log" -> log(value, problems);
        case "marshal", "unmarshal" -> dataFormat(value, problems, name);
        case "choice" -> choice(value, allowedHosts, named, problems);
        default -> throw new IllegalStateException(name);
      }
    }
  }

  private void choice(Node value, List<String> allowedHosts, Set<String> named, List<Problem> problems) {
    Map<String, Node> fields = value instanceof MappingNode m ? entries(m) : Map.of();
    for (String k : fields.keySet()) {
      if (!Set.of("when", "otherwise").contains(k)) {
        problems.add(new Problem(line(fields.get(k)), "choice", "choice may hold only when and otherwise"));
      }
    }
    if (fields.get("when") instanceof SequenceNode whens) {
      for (Node w : whens.getValue()) {
        Map<String, Node> when = w instanceof MappingNode m ? entries(m) : Map.of();
        expression(w, Set.of("steps"), problems, "when");
        if (when.get("steps") instanceof SequenceNode s) {
          walk(s, allowedHosts, named, problems);
        }
      }
    }
    if (fields.get("otherwise") instanceof MappingNode o && entries(o).get("steps") instanceof SequenceNode s) {
      walk(s, allowedHosts, named, problems);
    }
  }

  /** {@code node} holds one {@code constant} or {@code simple} expression besides {@code other} keys. */
  private static void expression(Node node, Set<String> other, List<Problem> problems, String construct) {
    if (!(node instanceof MappingNode map)) {
      return;
    }
    for (NodeTuple t : map.getValue()) {
      String k = key(t);
      if (other.contains(k)) {
        continue;
      }
      String language = k;
      Node text = t.getValueNode();
      if ("expression".equals(k) && text instanceof MappingNode inner && inner.getValue().size() == 1) {
        language = key(inner.getValue().getFirst());
        text = inner.getValue().getFirst().getValueNode();
      }
      if (!LANGUAGES.contains(language)) {
        problems.add(new Problem(line(t.getKeyNode()), construct,
            "expression language " + language + " is not allowed (constant or simple only)"));
        continue;
      }
      if (text instanceof MappingNode settings) {
        Map<String, Node> s = entries(settings);
        if (s.containsKey("resultType")) {
          problems.add(new Problem(line(text), construct, "an expression may not name a result type"));
        }
        text = s.get("expression");
      }
      if ("simple".equals(language)) {
        tokens(scalar(text), line(t.getKeyNode()), construct, problems);
      }
    }
  }

  private static void tokens(String text, int line, String construct, List<Problem> problems) {
    if (text == null) {
      return;
    }
    Matcher m = PLACEHOLDER.matcher(text);
    while (m.find()) {
      if (!SIMPLE_TOKEN.matcher(m.group(1).trim()).matches()) {
        problems.add(new Problem(line, construct,
            "simple may read only ${body}, ${header.x}, ${variable.x} and ${date:now}, not ${" + m.group(1) + "}"));
      }
    }
  }

  private static void log(Node value, List<Problem> problems) {
    String message;
    if (value instanceof ScalarNode s) {
      message = s.getValue();
    } else {
      Map<String, Node> fields = value instanceof MappingNode m ? entries(m) : Map.of();
      if (!Set.of("message", "loggingLevel", "logName").containsAll(fields.keySet())) {
        problems.add(new Problem(line(value), "log", "log may set only its message, level and name"));
      }
      message = scalar(fields.get("message"));
    }
    tokens(message, line(value), "log", problems);
    if (message != null && PLACEHOLDER.matcher(message).results().anyMatch(m -> m.group(1).trim().equals("body"))) {
      problems.add(new Problem(line(value), "log", "log may not write the body (personal data)"));
    }
  }

  private static void dataFormat(Node value, List<Problem> problems, String construct) {
    Map<String, Node> fields = value instanceof MappingNode m ? entries(m) : Map.of();
    boolean plainJson = fields.size() == 1 && fields.get("json") instanceof MappingNode jsonNode
        && entries(jsonNode).entrySet().stream().allMatch(e -> e.getKey().equals("library") && "Jackson".equals(scalar(e.getValue())));
    if (!plainJson) {
      problems.add(new Problem(line(value), construct, "marshal/unmarshal may use plain JSON (Jackson) only"));
    }
  }

  private void kamelet(Node value, List<String> allowedHosts, Set<String> named, List<Problem> problems) {
    int line = line(value);
    if (!(value instanceof ScalarNode s)) {
      problems.add(new Problem(line, "to", "'to' must be a plain \"kamelet:mw-...\" string"));
      return;
    }
    String uri = s.getValue();
    if (!uri.startsWith("kamelet:mw-")) {
      int colon = uri.indexOf(':');
      problems.add(new Problem(line, "to",
          "'to' may call only the mw-* Kamelets (raw endpoints are refused): " + (colon < 0 ? "?" : uri.substring(0, colon))));
      return;
    }
    if (PartnerBatchRouteRules.handles(uri)) {
      // ticket 86: the SFTP and recon Kamelets have their own short parameter lists
      String batchName = uri.substring("kamelet:".length(), uri.indexOf('?') < 0 ? uri.length() : uri.indexOf('?'));
      PartnerBatchRouteRules.problems(uri, false, allowedHosts).forEach(p -> problems.add(new Problem(line, batchName, p)));
      return;
    }
    if (uri.contains("${") || uri.contains("$simple{")) {
      problems.add(new Problem(line, "to", "a Kamelet URI must be static (no ${...})"));
      return;
    }
    int q = uri.indexOf('?');
    String name = uri.substring("kamelet:".length(), q < 0 ? uri.length() : q);
    if (!kamelets.contains(name)) {
      problems.add(new Problem(line, "to", "Kamelet " + name + " is not in the library"));
      return;
    }
    Map<String, String> params = new LinkedHashMap<>();
    if (q >= 0) {
      for (String pair : uri.substring(q + 1).split("&")) {
        if (pair.isEmpty()) {
          continue;
        }
        int eq = pair.indexOf('=');
        String key = eq < 0 ? pair : pair.substring(0, eq);
        String raw = eq < 0 ? "" : pair.substring(eq + 1);
        String problem = parameterProblem(key, raw, params);
        if (problem != null) {
          problems.add(new Problem(line, name, problem));
          return;
        }
        params.put(key, URLDecoder.decode(raw, StandardCharsets.UTF_8));
      }
    }
    if (name.startsWith("mw-http-call")) {
      String auth = params.getOrDefault("auth", "none");
      if (!kamelets.contains("mw-auth-" + auth)) {
        problems.add(new Problem(line, name, "auth " + auth + " has no mw-auth Kamelet"));
      } else if (!"none".equals(auth) && params.getOrDefault("secretField", "").isEmpty()) {
        problems.add(new Problem(line, name, "auth " + auth + " needs a secretField"));
      }
      String path = params.getOrDefault("path", "");
      if (!SAFE_PATH.matcher(path).matches() || path.contains("//")
          || java.util.Arrays.stream(path.split("/")).anyMatch(".."::equals)) {
        problems.add(new Problem(line, name, "mw-http-call path must be an absolute path of letters, digits, . _ ~ - /"
            + " and {reference}, without .. segments or //"));
      }
      hostProblem(params.getOrDefault("url", ""), allowedHosts).ifPresent(p -> problems.add(new Problem(line, name, p)));
    }
    if (name.startsWith("mw-soap-call")) {
      soapCall(params, line, name, allowedHosts, named, problems);
    }
    if (params.containsKey("mapping")) {
      named.add(params.get("mapping"));
    }
  }

  /**
   * The loader's rules of one {@code mw-soap-call} (ticket 85): an approved http(s) address,
   * request and response stylesheets that are {@code *.xsl} mappings of the record (checked with
   * {@link StylesheetPolicy} in {@link #problems}) or {@code classpath:partner/xslt/*.xsl}
   * resources, SOAP 1.1 or 1.2, a WS-Security user only with its password's secret field.
   */
  private static void soapCall(Map<String, String> params, int line, String name, List<String> allowedHosts,
      Set<String> named, List<Problem> problems) {
    hostProblem(params.getOrDefault("address", ""), allowedHosts)
        .ifPresent(p -> problems.add(new Problem(line, name, p.replace("mw-http-call url", "mw-soap-call address")
            .replace("mw-http-call host", "mw-soap-call host"))));
    for (String key : List.of("request", "response")) {
      String stylesheet = params.getOrDefault(key, "");
      if (CLASSPATH_STYLESHEET.matcher(stylesheet).matches()) {
        continue;
      }
      if (stylesheet.endsWith(".xsl")) {
        named.add(stylesheet);
      } else {
        problems.add(new Problem(line, name, "mw-soap-call " + key + " must name one of the record's *.xsl mappings"
            + " or a classpath:partner/xslt/*.xsl resource"));
      }
    }
    if (!Set.of("1.1", "1.2").contains(params.getOrDefault("soapVersion", "1.1"))) {
      problems.add(new Problem(line, name, "mw-soap-call soapVersion must be 1.1 or 1.2"));
    }
    if (!Set.of("PasswordText", "PasswordDigest").contains(params.getOrDefault("wsTokenType", "PasswordText"))) {
      problems.add(new Problem(line, name, "mw-soap-call wsTokenType must be PasswordText or PasswordDigest"));
    }
    if (!params.getOrDefault("wsUsername", "").isEmpty() && params.getOrDefault("wsPasswordSecret", "").isEmpty()) {
      problems.add(new Problem(line, name, "mw-soap-call wsUsername needs a wsPasswordSecret"));
    }
  }

  /** The loader's rule for one Kamelet parameter; null when it is accepted. */
  static String parameterProblem(String key, String raw, Map<String, String> seen) {
    if (!PARAM_KEY.matcher(key).matches()) {
      return "Kamelet parameter names are letters and digits";
    }
    if (seen.containsKey(key)) {
      return "Kamelet parameter " + key + " is set twice";
    }
    if (RESERVED_KAMELET_PARAMS.contains(key.toLowerCase(Locale.ROOT))) {
      return "Kamelet parameter " + key + " is set by the loader, never by the route";
    }
    if (raw.startsWith("RAW(") && raw.endsWith(")")) {
      return "Kamelet parameter " + key + ": RAW(...) is not allowed";
    }
    if ((STRICT_PARAMS.contains(key) || SOAP_STRICT_PARAMS.contains(key)) && raw.indexOf('%') >= 0) {
      return "Kamelet parameter " + key + " may not be percent-encoded";
    }
    String decoded;
    try {
      decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return "Kamelet parameter " + key + " is not a valid URI value";
    }
    if (decoded.contains("{{") || decoded.contains("${") || decoded.contains("$simple{")
        || ((STRICT_PARAMS.contains(key) || SOAP_STRICT_PARAMS.contains(key))
            && decoded.chars().anyMatch(c -> "%?&#".indexOf(c) >= 0))) {
      return "Kamelet parameter " + key + " has a character that is not allowed";
    }
    return null;
  }

  private static java.util.Optional<String> hostProblem(String url, List<String> allowedHosts) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      return java.util.Optional.of("mw-http-call url is not a URI");
    }
    if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
        || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
      return java.util.Optional.of("mw-http-call url must be an absolute http(s) URL without user info, query or fragment");
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (allowedHosts == null || allowedHosts.stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
      return java.util.Optional.of("mw-http-call host is not in the record's allowedHosts");
    }
    return java.util.Optional.empty();
  }

  // --- the end of the route (loader step 8) -----------------------------------------------------

  private static void ending(SequenceNode steps, List<Problem> problems) {
    List<Node> outputs = steps.getValue();
    int n = outputs.size();
    if (n < 2 || !isKamelet(outputs.get(n - 1), "mw-audit")) {
      problems.add(new Problem(line(steps), "route end", "the route must end with mw-classify then mw-audit"));
      return;
    }
    Node before = outputs.get(n - 2);
    if (isKamelet(before, "mw-classify")) {
      return;
    }
    Node choice = stepValue(before, "choice");
    if (choice instanceof MappingNode c) {
      Map<String, Node> fields = entries(c);
      if (fields.get("when") instanceof SequenceNode whens && !whens.getValue().isEmpty()
          && whens.getValue().stream().allMatch(w -> w instanceof MappingNode m && endsWithClassify(entries(m).get("steps")))
          && (fields.get("otherwise") == null
              || (fields.get("otherwise") instanceof MappingNode o && endsWithClassify(entries(o).get("steps"))))) {
        return;
      }
    }
    problems.add(new Problem(line(before), "route end", "the route must end with mw-classify then mw-audit"));
  }

  /** Ticket 86: a batch route ends with {@code mw-recon}. */
  private static void batchEnding(SequenceNode steps, List<Problem> problems) {
    List<Node> outputs = steps.getValue();
    if (outputs.isEmpty() || !isKamelet(outputs.getLast(), PartnerBatchRouteRules.RECON)) {
      problems.add(new Problem(line(steps), "route end", "a batch route must end with mw-recon"));
    }
  }

  /** True when {@code route} (YAML text) starts from {@code kamelet:mw-sftp-source} (ticket 86). */
  public static boolean isBatch(String route) {
    if (route == null) {
      return false;
    }
    try {
      Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(route);
      return isBatch(doc);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static boolean isBatch(Object doc) {
    return doc instanceof List<?> list && list.size() == 1 && list.getFirst() instanceof Map<?, ?> item
        && item.get("route") instanceof Map<?, ?> route && route.get("from") instanceof Map<?, ?> from
        && PartnerBatchRouteRules.isBatchUri(from.get("uri") instanceof String s ? s : null);
  }

  private static boolean endsWithClassify(Node steps) {
    return steps instanceof SequenceNode s && !s.getValue().isEmpty() && isKamelet(s.getValue().getLast(), "mw-classify");
  }

  private static boolean isKamelet(Node step, String name) {
    String uri = scalar(stepValue(step, "to"));
    return uri != null && (uri.equals("kamelet:" + name) || uri.startsWith("kamelet:" + name + "?"));
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static Node stepValue(Node step, String name) {
    if (step instanceof MappingNode m && m.getValue().size() == 1 && name.equals(key(m.getValue().getFirst()))) {
      return m.getValue().getFirst().getValueNode();
    }
    return null;
  }

  private static Map<String, Node> entries(MappingNode map) {
    Map<String, Node> out = new LinkedHashMap<>();
    for (NodeTuple t : map.getValue()) {
      out.put(key(t), t.getValueNode());
    }
    return out;
  }

  private static String key(NodeTuple t) {
    return t.getKeyNode() instanceof ScalarNode s ? s.getValue() : "";
  }

  private static String scalar(Node node) {
    return node instanceof ScalarNode s ? s.getValue() : null;
  }

  private static int line(Node node) {
    return node == null || node.getStartMark() == null ? 0 : node.getStartMark().getLine() + 1;
  }

  private static int lineOf(String text, int index) {
    int line = 1;
    for (int i = 0; i < index; i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  private static String firstLine(String s) {
    if (s == null) {
      return "";
    }
    int nl = s.indexOf('\n');
    return nl < 0 ? s : s.substring(0, nl);
  }
}
