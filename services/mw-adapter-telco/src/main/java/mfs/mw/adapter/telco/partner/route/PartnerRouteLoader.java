package mfs.mw.adapter.telco.partner.route;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.adapter.telco.partner.auth.DetachedJws;
import mfs.mw.adapter.telco.partner.auth.PartnerCredentials;
import mfs.mw.adapter.telco.partner.auth.PartnerTlsContexts;
import mfs.mw.adapter.telco.partner.config.PartnerConfig;
import mfs.mw.adapter.telco.partner.config.PartnerConfigRefusedException;
import mfs.mw.adapter.telco.partner.config.PartnerEndpointPolicy;
import mfs.mw.adapter.telco.partner.mapping.CompiledMapping;
import mfs.mw.adapter.telco.partner.mapping.CompiledStylesheet;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.apache.camel.CamelContext;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.builder.RouteConfigurationBuilder;
import org.apache.camel.model.ChoiceDefinition;
import org.apache.camel.model.ExpressionNode;
import org.apache.camel.model.LogDefinition;
import org.apache.camel.model.MarshalDefinition;
import org.apache.camel.model.OtherwiseDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.RoutesDefinition;
import org.apache.camel.model.SetBodyDefinition;
import org.apache.camel.model.SetHeaderDefinition;
import org.apache.camel.model.ToDefinition;
import org.apache.camel.model.TransformDefinition;
import org.apache.camel.model.UnmarshalDefinition;
import org.apache.camel.model.WhenDefinition;
import org.apache.camel.model.DataFormatDefinition;
import org.apache.camel.model.dataformat.JsonDataFormat;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.model.language.ExpressionDefinition;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.support.ResourceHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Checks a partner record and builds its route (ADR D26, guardrail 2), never touching the running
 * routes: {@link PartnerRoutes} adds what this returns. A record is refused, with every reason,
 * when anything fails; the caller keeps the last good version live.
 *
 * <p>Steps, in this order (the YAML is checked before Camel sees it, because Camel's YAML loader
 * acts on some top-level nodes while it parses: {@code beans}, {@code sslContextParameters}, {@code
 * restConfiguration}):
 *
 * <ol>
 *   <li>the record ({@link PartnerConfig#problems()}; a record without a route is refused);
 *   <li>no {@code {{...}}} anywhere (property placeholders reach {@code env:}, {@code sys:}, the
 *       vault function, beans);
 *   <li>safe YAML: exactly one {@code - route:} whose only key is {@code from}, with only {@code
 *       uri: direct:partner-<id>} and {@code steps}; the partner sets no route attribute (id,
 *       error handler, policies, message history, trace, startup) and nothing else at top level;
 *   <li>Camel's published YAML DSL JSON Schema ({@code schema/camelYamlDsl.json} of {@code
 *       camel-yaml-dsl}), for clear errors;
 *   <li>Kamelet calls: {@code to: "kamelet:mw-*"} strings only, of the shipped library, without
 *       the parameters that change how Camel resolves a Kamelet ({@code location}, {@code
 *       templateId}, ...) or that the loader sets ({@code partnerId}, timeouts, bulkhead); the
 *       loader injects those from the record, then route id {@code partner-<id>-v<version>}, {@code
 *       from: direct:partner-<id>-v<version>} and the loader's {@code routeConfiguration} {@value
 *       PartnerRouteConfiguration#ID};
 *   <li>Camel parses it ({@code RoutesLoader}); exactly one route, nothing else;
 *   <li>the parsed model against an allowlist of node types: {@code choice}/{@code when}/{@code
 *       otherwise}, {@code to} (Kamelets above), {@code setHeader} (not {@code Camel*} or {@code
 *       mw*}), {@code setBody}, {@code transform}, {@code log} (no body), {@code marshal}/{@code
 *       unmarshal} JSON; expressions {@code constant} or {@code simple} with {@code ${body}},
 *       {@code ${header.x}}, {@code ${variable.x}}, {@code ${date:...}} only. Anything else is
 *       refused ({@code bean}, {@code process}, raw endpoints, {@code toD}, {@code onException},
 *       ...);
 *   <li>the route ends with {@code mw-classify} (directly, or at the end of every branch of a
 *       final {@code choice}) and then {@code mw-audit};
 *   <li>every {@code mw-http-call} URL against the record's allowed hosts ({@link
 *       PartnerEndpointPolicy}); every mapping the route names exists and compiles; every secret
 *       field it names is read from OpenBao now ({@link PartnerCredentials}).
 * </ol>
 */
@Component
public class PartnerRouteLoader {

  /** Kamelet parameters that end up in the HTTP request: no {@code % ? & #}, before or after decoding. */
  static final Set<String> STRICT_PARAMS = Set.of("url", "path", "authHeader", "keyId", "method", "auth", "secretField",
      "userField", "tokenUrl", "clientId", "scope", "audience", "alg", "sslContextRef");

  /** {@code mw-soap-call} parameters held to the same rule (ticket 85). */
  static final Set<String> SOAP_STRICT_PARAMS =
      Set.of("address", "soapAction", "soapVersion", "request", "response", "wsUsername", "wsPasswordSecret", "wsTokenType");

  /** A stylesheet shipped with the service: {@code classpath:partner/xslt/<name>.xsl}. */
  static final Pattern CLASSPATH_STYLESHEET = Pattern.compile("^classpath:partner/xslt/[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}\\.xsl$");

  private static final Pattern PARAM_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9]*$");

  static final Set<String> RESERVED_KAMELET_PARAMS =
      Set.of("partnerid", "timeoutms", "connecttimeoutms", "readtimeoutms", "bulkhead",
          "location", "templateid", "routeid", "uuid", "noerrorhandler", "bridgeerrorhandler");

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$(?:simple)?\\{([^}]*)}");
  private static final Pattern SIMPLE_TOKEN =
      Pattern.compile("^(body|header\\.[A-Za-z0-9_-]+|headers\\.[A-Za-z0-9_-]+|variable\\.[A-Za-z0-9_-]+|date:now(:[A-Za-z0-9 .:'_-]*)?)$");
  private static final Set<Class<?>> ALLOWED_NODES =
      Set.of(ChoiceDefinition.class, OtherwiseDefinition.class, ToDefinition.class,
          SetHeaderDefinition.class, SetBodyDefinition.class, TransformDefinition.class, LogDefinition.class,
          MarshalDefinition.class, UnmarshalDefinition.class);

  private final CamelContext camel;
  private final PartnerEndpointPolicy policy;
  private final KameletCatalogue kamelets;
  private final PartnerCredentials credentials;
  private final BulkheadRegistry bulkheads;
  private final PartnerTlsContexts tls;
  private volatile Schema yamlDslSchema;

  public PartnerRouteLoader(
      CamelContext camel,
      PartnerEndpointPolicy policy,
      KameletCatalogue kamelets,
      PartnerCredentials credentials,
      BulkheadRegistry bulkheads) {
    this(camel, policy, kamelets, credentials, bulkheads, PartnerTlsContexts.none());
  }

  @Autowired
  public PartnerRouteLoader(
      CamelContext camel,
      PartnerEndpointPolicy policy,
      KameletCatalogue kamelets,
      PartnerCredentials credentials,
      BulkheadRegistry bulkheads,
      PartnerTlsContexts tls) {
    this.camel = camel;
    this.policy = policy;
    this.kamelets = kamelets;
    this.credentials = credentials;
    this.bulkheads = bulkheads;
    this.tls = tls;
  }

  /**
   * A checked record: its runtime, the route definition to add and the registry beans it refers to
   * (ticket 86: a batch route's SFTP credential and known hosts, bound by {@link PartnerRoutes}
   * before the route starts and removed with it; never in an endpoint URI).
   */
  public record Loaded(PartnerRuntime runtime, RouteDefinition route, Map<String, String> beans) {
    public Loaded {
      beans = Map.copyOf(beans);
    }

    public Loaded(PartnerRuntime runtime, RouteDefinition route) {
      this(runtime, route, Map.of());
    }
  }

  /**
   * Checks record {@code config} as version {@code version} of partner {@code id}.
   *
   * @throws PartnerConfigRefusedException listing every reason; {@code security()} when one breaks
   *     a security rule
   */
  public Loaded load(String id, long version, PartnerConfig config) {
    return load(id, version, config, null);
  }

  /**
   * Where a candidate's {@code mw-http-call}s go during its dry-run (ticket 83): {@code baseUrl}
   * replaces the scheme, host and port of every call URL (the URL's own path is kept after it), and
   * {@code extraHost}, when set (the environment's simulator, static config), is added to the
   * record's allowed hosts. A record's own {@code dryRun.baseUrl} has no extra host: it must be in
   * the record's allowed hosts.
   */
  public record DryRunTarget(String baseUrl, String extraHost) {}

  /**
   * As {@link #load(String, long, PartnerConfig)}; with a {@code
   * candidate} target, builds the dry-run candidate instead: route {@code
   * partner-<id>~candidate~v<version>} from {@code direct:partner-<id>~candidate}, its calls sent
   * to the target, on a circuit breaker of its own ({@code mw-cb-<id>~candidate}). Every check runs
   * as for the live version, and the target URL is rebuilt from its parsed parts and checked as a
   * strict parameter (no {@code % ? & #}, no placeholder) before it replaces a call URL.
   */
  public Loaded load(
      String id, long version, PartnerConfig config, DryRunTarget candidate) {
    List<String> security = new ArrayList<>(policy.idProblems(id));
    List<String> problems = new ArrayList<>();
    if (!security.isEmpty()) {
      throw new PartnerConfigRefusedException(id, security, true);
    }
    for (String p : config.problems()) {
      if (p.startsWith(PartnerConfig.SECURITY)) {
        security.add(p.substring(PartnerConfig.SECURITY.length()));
      } else {
        problems.add(p);
      }
    }
    refuseIfAny(id, security, problems);
    PartnerConfig record = config;
    List<String> allowed = policy.allowedHosts(id, record.allowedHosts());
    if (candidate != null && candidate.extraHost() != null && !allowed.contains(candidate.extraHost())) {
      allowed = new ArrayList<>(allowed);
      allowed.add(candidate.extraHost());
    }

    String text = record.route();
    if (text.contains("{{")) {
      security.add("property placeholders ({{...}}) are not allowed in a partner route");
      refuseIfAny(id, security, problems);
    }
    Map<String, Object> from = structure(id, text, security, problems);
    refuseIfAny(id, security, problems);
    // the parsed values, not the text: YAML escapes, quoting and block scalars can hide a placeholder
    if (anyString(from, v -> v.contains("{{") || v.contains("}}"))) {
      security.add("property placeholders ({{...}}) are not allowed in a partner route");
    }
    refuseIfAny(id, security, problems);
    schemaProblems(text, problems);
    refuseIfAny(id, security, problems);

    String routeId = candidate == null ? PartnerRuntime.routeId(id, version) : PartnerRuntime.candidateRouteId(id, version);
    String endpoint = candidate == null ? PartnerRuntime.endpoint(id, version) : PartnerRuntime.candidateEndpoint(id);
    Calls calls = new Calls();
    calls.partnerParam = candidate == null ? id : PartnerRuntime.candidatePartnerParam(id);
    calls.dryRunBase = candidate == null ? null : candidate.baseUrl().replaceAll("/+$", "");
    // ticket 86: a batch route starts from the mw-sftp-source Kamelet instead of direct:partner-<id>
    calls.batch = PartnerBatchRoutes.isBatchUri(from.get("uri"));
    calls.routeId = routeId;
    String batchFrom = null;
    if (calls.batch) {
      if (record.dryRun() != null || candidate != null) {
        problems.add("a batch route has no dry-run (nothing to send a sample to)");
      }
      batchFrom = PartnerBatchRoutes.kamelet((String) from.get("uri"), true, calls.batchCalls(), security);
    }
    injectKamelets(from, id, record, calls, security, problems);
    refuseIfAny(id, security, problems);
    from.put("uri", calls.batch ? batchFrom : endpoint);
    Map<String, Object> route = new LinkedHashMap<>();
    route.put("id", routeId);
    if (!calls.batch) {
      // a batch route's failure must reach the file consumer (moveFailed): no handled onException
      route.put("routeConfigurationId", PartnerRouteConfiguration.ID);
    }
    route.put("from", from);

    String loaded = dump(route);
    if (loaded.contains("{{")) {
      security.add("property placeholders ({{...}}) are not allowed in a partner route");
      refuseIfAny(id, security, problems);
    }
    RouteDefinition definition = parse(id, routeId, loaded, security, problems);
    refuseIfAny(id, security, problems);
    List<ProcessorDefinition<?>> outputs = partnerOutputs(definition);
    walk(outputs, security, problems);
    if (calls.batch) {
      PartnerBatchRoutes.ending(outputs, problems);
    } else {
      ending(outputs, problems);
    }
    refuseIfAny(id, security, problems);
    for (String host : calls.sftp.hosts) {
      security.addAll(policy.hostProblems(id, host, allowed));
    }

    for (String url : calls.urls) {
      URI uri;
      try {
        uri = URI.create(url);
      } catch (IllegalArgumentException e) {
        security.add("mw-http-call url is not a URI");
        continue;
      }
      security.addAll(policy.problems(id, uri, allowed));
    }
    for (String path : calls.paths) {
      if (!PartnerConfig.safePath(path)) {
        security.add("mw-http-call path must be an absolute path of letters, digits, . _ ~ - / and {reference},"
            + " without .. segments or //");
      }
    }
    Map<String, CompiledMapping> mappings = new LinkedHashMap<>();
    record.mappings().forEach((name, source) -> {
      if (!CompiledStylesheet.isStylesheet(name)) {
        mappings.put(name, CompiledMapping.compile(source));
      }
    });
    for (String name : calls.mappings) {
      if (!mappings.containsKey(name)) {
        problems.add("the route names mapping " + name + ", which the record does not have");
      }
    }
    Map<String, CompiledStylesheet> stylesheets = stylesheets(record, calls, problems);
    refuseIfAny(id, security, problems);

    Map<String, String> values = new LinkedHashMap<>();
    Set<String> fields = new TreeSet<>(calls.secretFields);
    fields.addAll(calls.sftp.beans.values());
    if (record.auth().credentialsRef() != null) {
      fields.add(record.auth().credentialsRef());
    }
    for (String field : fields) {
      try {
        values.put(field, credentials.read(id, field));
      } catch (IllegalStateException e) {
        problems.add(e.getMessage());
      }
    }
    refuseIfAny(id, security, problems);
    Map<String, String> beans = new LinkedHashMap<>();
    calls.sftp.beans.forEach((bean, field) -> beans.put(bean, values.get(field)));
    return new Loaded(
        new PartnerRuntime(id, version, record, routeId, endpoint, mappings, values,
            allowed, calls.kamelets, stylesheets),
        definition, beans);
  }

  private static void refuseIfAny(String id, List<String> security, List<String> problems) {
    if (!security.isEmpty() || !problems.isEmpty()) {
      List<String> all = new ArrayList<>(security);
      all.addAll(problems);
      throw new PartnerConfigRefusedException(id, all, !security.isEmpty());
    }
  }

  // --- 3. structure ---------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static Map<String, Object> structure(String id, String text, List<String> security, List<String> problems) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setAllowRecursiveKeys(false);
    Object doc;
    try {
      doc = new Yaml(new SafeConstructor(options)).load(text);
    } catch (RuntimeException e) {
      problems.add("the route is not valid YAML: " + firstLine(e.getMessage()));
      return Map.of();
    }
    if (!(doc instanceof List<?> list) || list.size() != 1 || !(list.getFirst() instanceof Map<?, ?> item)
        || item.size() != 1 || !item.containsKey("route")) {
      security.add("a partner route is exactly one '- route:' and nothing else (no beans, routeConfiguration,"
          + " errorHandler, onException, intercept, rest, templates, sslContextParameters)");
      return Map.of();
    }
    if (!(item.get("route") instanceof Map<?, ?> route) || route.size() != 1 || !route.containsKey("from")) {
      security.add("the route may hold only 'from' (id, error handler, policies, message history, trace,"
          + " startup and the like are set by the loader or not at all)");
      return Map.of();
    }
    if (!(route.get("from") instanceof Map<?, ?> from)
        || !Set.of("uri", "steps").containsAll(from.keySet())
        || !(from.get("steps") instanceof List<?>)) {
      security.add("'from' may hold only 'uri' and 'steps'");
      return Map.of();
    }
    if (forbiddenKey(from.get("steps"))) {
      security.add("a partner route may not declare onException, errorHandler, intercept*, onCompletion,"
          + " doTry, routeConfiguration or circuitBreaker; the loader owns error handling");
    }
    if (!("direct:partner-" + id).equals(from.get("uri")) && !PartnerBatchRoutes.isBatchUri(from.get("uri"))) {
      problems.add("the route must start from direct:partner-" + id + " (or kamelet:mw-sftp-source for a batch route)");
    }
    return (Map<String, Object>) from;
  }

  private static final Set<String> FORBIDDEN_KEYS =
      Set.of("onException", "errorHandler", "intercept", "interceptFrom", "interceptSendToEndpoint",
          "onCompletion", "doTry", "doCatch", "doFinally", "routeConfiguration", "circuitBreaker", "onFallback");

  /** True when a key or string value anywhere in {@code node} matches {@code test}. */
  private static boolean anyString(Object node, java.util.function.Predicate<String> test) {
    if (node instanceof String s) {
      return test.test(s);
    }
    if (node instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (anyString(String.valueOf(e.getKey()), test) || anyString(e.getValue(), test)) {
          return true;
        }
      }
    } else if (node instanceof List<?> list) {
      for (Object o : list) {
        if (anyString(o, test)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean forbiddenKey(Object node) {
    if (node instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (FORBIDDEN_KEYS.contains(String.valueOf(e.getKey())) || forbiddenKey(e.getValue())) {
          return true;
        }
      }
    } else if (node instanceof List<?> list) {
      for (Object o : list) {
        if (forbiddenKey(o)) {
          return true;
        }
      }
    }
    return false;
  }

  // --- 4. JSON Schema -------------------------------------------------------------------------

  private void schemaProblems(String text, List<String> problems) {
    Schema schema = yamlDslSchema();
    List<com.networknt.schema.Error> errors = schema.validate(text, InputFormat.YAML);
    errors.stream().limit(5).forEach(e -> problems.add("YAML DSL schema: " + e.getInstanceLocation() + " " + e.getMessage()));
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

  // --- 5. Kamelet calls -----------------------------------------------------------------------

  /** What the route calls, collected while the loader injects the record's parameters. */
  private static final class Calls {
    final Set<String> kamelets = new LinkedHashSet<>();
    final List<String> urls = new ArrayList<>();
    final List<String> paths = new ArrayList<>();
    final Set<String> mappings = new LinkedHashSet<>();
    final Set<String> secretFields = new LinkedHashSet<>();
    /** The stylesheets {@code mw-soap-call} names (record {@code *.xsl} mappings or classpath resources). */
    final Set<String> stylesheets = new LinkedHashSet<>();
    /** The {@code partnerId} injected into each Kamelet (it names the circuit breaker). */
    String partnerParam;
    /** A candidate's dry-run base URL; null for a live version. */
    String dryRunBase;
    /** Ticket 86: the route starts from mw-sftp-source. */
    boolean batch;
    String routeId;
    /** Ticket 86: what the SFTP and recon Kamelets name (hosts, secret beans). */
    final PartnerBatchRoutes.Calls sftp = new PartnerBatchRoutes.Calls();

    PartnerBatchRoutes.Calls batchCalls() {
      sftp.partnerId = partnerParam;
      sftp.routeId = routeId;
      sftp.batch = batch;
      sftp.kamelets = kamelets;
      return sftp;
    }
  }

  @SuppressWarnings("unchecked")
  private void injectKamelets(
      Object node, String id, PartnerConfig record, Calls calls, List<String> security, List<String> problems) {
    if (node instanceof List<?> list) {
      for (Object o : list) {
        injectKamelets(o, id, record, calls, security, problems);
      }
    } else if (node instanceof Map<?, ?> raw) {
      Map<String, Object> map = (Map<String, Object>) raw;
      for (Map.Entry<String, Object> e : map.entrySet()) {
        if ("to".equals(e.getKey())) {
          if (!(e.getValue() instanceof String uri)) {
            security.add("'to' must be a plain \"kamelet:mw-...\" string");
            continue;
          }
          String injected = kamelet(uri, id, record, calls, security, problems);
          if (injected != null) {
            e.setValue(injected);
          }
        } else {
          injectKamelets(e.getValue(), id, record, calls, security, problems);
        }
      }
    }
  }

  private String kamelet(
      String uri, String id, PartnerConfig record, Calls calls, List<String> security, List<String> problems) {
    if (!uri.startsWith("kamelet:mw-")) {
      security.add("'to' may call only the mw-* Kamelets (raw endpoints are refused): " + scheme(uri));
      return null;
    }
    if (PartnerBatchRoutes.handles(uri)) {
      // ticket 86: the SFTP and recon Kamelets have their own short parameter lists
      return PartnerBatchRoutes.kamelet(uri, false, calls.batchCalls(), security);
    }
    if (uri.contains("${") || uri.contains("$simple{")) {
      security.add("a Kamelet URI must be static (no ${...})");
      return null;
    }
    int q = uri.indexOf('?');
    String name = uri.substring("kamelet:".length(), q < 0 ? uri.length() : q);
    if (!kamelets.contains(name)) {
      security.add("Kamelet " + name + " is not in the library");
      return null;
    }
    Map<String, String> params = new LinkedHashMap<>();
    if (q >= 0) {
      for (String pair : uri.substring(q + 1).split("&")) {
        if (pair.isEmpty()) {
          continue;
        }
        int eq = pair.indexOf('=');
        String key = eq < 0 ? pair : pair.substring(0, eq);
        String value = eq < 0 ? "" : pair.substring(eq + 1);
        // a plain name, once: no encoding or odd character can make it another key for Camel
        if (!PARAM_KEY.matcher(key).matches()) {
          security.add("Kamelet parameter names are letters and digits");
          return null;
        }
        if (params.containsKey(key)) {
          security.add("Kamelet parameter " + key + " is set twice");
          return null;
        }
        if (RESERVED_KAMELET_PARAMS.contains(key.toLowerCase(Locale.ROOT))) {
          security.add("Kamelet parameter " + key + " is set by the loader, never by the route");
          return null;
        }
        if (value.startsWith("RAW(") && value.endsWith(")")) {
          security.add("Kamelet parameter " + key + ": RAW(...) is not allowed");
          return null;
        }
        if ((STRICT_PARAMS.contains(key) || SOAP_STRICT_PARAMS.contains(key)) && value.indexOf('%') >= 0) {
          security.add("Kamelet parameter " + key + " may not be percent-encoded");
          return null;
        }
        // as Camel reads it
        String decoded;
        try {
          decoded = java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
          security.add("Kamelet parameter " + key + " is not a valid URI value");
          return null;
        }
        if (decoded.contains("{{") || decoded.contains("${") || decoded.contains("$simple{")
            || ((STRICT_PARAMS.contains(key) || SOAP_STRICT_PARAMS.contains(key))
                && decoded.chars().anyMatch(c -> "%?&#".indexOf(c) >= 0))) {
          security.add("Kamelet parameter " + key + " has a character that is not allowed");
          return null;
        }
        params.put(key, decoded);
      }
    }
    calls.kamelets.add(name);
    boolean soap = name.startsWith("mw-soap-call");
    String urlParam = soap ? "address" : "url";
    if (calls.dryRunBase != null && (name.startsWith("mw-http-call") || soap)) {
      // the keys are plain names, set once, and url holds no % ? & #: one textual swap is exact
      String target = dryRunUrl(calls.dryRunBase, params.getOrDefault(urlParam, ""));
      if (target == null) {
        security.add("the dry-run target must be a plain http(s) URL: no user info, query, fragment, encoding,"
            + " placeholder or .. segment");
        return null;
      }
      uri = uri.replaceFirst("([?&])" + urlParam + "=[^&]*", "$1" + urlParam + "=" + java.util.regex.Matcher.quoteReplacement(target));
      params.put(urlParam, target);
    }
    StringBuilder out = new StringBuilder(uri).append(q < 0 ? '?' : '&').append("partnerId=").append(calls.partnerParam);
    if (name.startsWith("mw-http-call")) {
      calls.urls.add(params.getOrDefault("url", ""));
      calls.paths.add(params.getOrDefault("path", ""));
      String auth = params.getOrDefault("auth", "none");
      if (!kamelets.contains("mw-auth-" + auth)) {
        security.add("auth " + auth + " has no mw-auth Kamelet");
      } else if (!"none".equals(auth)) {
        String field = params.getOrDefault("secretField", "");
        if (field.isEmpty()) {
          problems.add("auth " + auth + " needs a secretField");
        } else {
          calls.secretFields.add(field);
        }
        authProblems(id, auth, params, calls, security, problems);
      }
      String tlsRef = params.getOrDefault("sslContextRef", "");
      if (!tlsRef.isEmpty()) {
        if (!tls.owner(tlsRef).map(id::equals).orElse(false)) {
          security.add("sslContextRef " + tlsRef + " is not a TLS client context of this partner");
        } else if (!params.getOrDefault("url", "").toLowerCase(Locale.ROOT).startsWith("https://")) {
          security.add("sslContextRef needs an https url");
        }
      }
      out.append("&timeoutMs=").append(record.timeoutMs())
          .append("&connectTimeoutMs=").append(record.connectTimeoutMs())
          .append("&readTimeoutMs=").append(record.readTimeoutMs())
          .append("&bulkhead=").append(bulkhead(id, record));
    }
    if (soap) {
      soapCall(params, record, calls, problems);
      calls.urls.add(params.getOrDefault("address", ""));
      out.append("&timeoutMs=").append(record.timeoutMs())
          .append("&connectTimeoutMs=").append(record.connectTimeoutMs())
          .append("&readTimeoutMs=").append(record.readTimeoutMs())
          .append("&bulkhead=").append(bulkhead(id, record));
    }
    if (params.containsKey("mapping")) {
      calls.mappings.add(params.get("mapping"));
    }
    return out.toString();
  }

  /**
   * The rules of one {@code mw-soap-call} (ticket 85): request and response stylesheets that are
   * the record's {@code *.xsl} mappings or {@code classpath:partner/xslt/*.xsl} resources, SOAP
   * 1.1 or 1.2, a WS-Security user only with the secret field of its password (read now).
   */
  private static void soapCall(Map<String, String> params, PartnerConfig record, Calls calls, List<String> problems) {
    for (String key : List.of("request", "response")) {
      String name = params.getOrDefault(key, "");
      if (CLASSPATH_STYLESHEET.matcher(name).matches()) {
        calls.stylesheets.add(name);
      } else if (CompiledStylesheet.isStylesheet(name) && record.mappings().containsKey(name)) {
        calls.stylesheets.add(name);
      } else {
        problems.add("mw-soap-call " + key + " must name one of the record's *.xsl mappings or a"
            + " classpath:partner/xslt/*.xsl resource");
      }
    }
    if (!Set.of("1.1", "1.2").contains(params.getOrDefault("soapVersion", "1.1"))) {
      problems.add("mw-soap-call soapVersion must be 1.1 or 1.2");
    }
    if (!Set.of("PasswordText", "PasswordDigest").contains(params.getOrDefault("wsTokenType", "PasswordText"))) {
      problems.add("mw-soap-call wsTokenType must be PasswordText or PasswordDigest");
    }
    String user = params.getOrDefault("wsUsername", "");
    String secret = params.getOrDefault("wsPasswordSecret", "");
    if (!user.isEmpty() && secret.isEmpty()) {
      problems.add("mw-soap-call wsUsername needs a wsPasswordSecret");
    } else if (!secret.isEmpty()) {
      calls.secretFields.add(secret);
    }
  }

  /** The stylesheets {@code calls} names, compiled; a missing resource is a problem. */
  private Map<String, CompiledStylesheet> stylesheets(PartnerConfig record, Calls calls, List<String> problems) {
    Map<String, CompiledStylesheet> out = new LinkedHashMap<>();
    for (String name : calls.stylesheets) {
      String source = name.startsWith("classpath:") ? resource(name.substring("classpath:".length())) : record.mappings().get(name);
      if (source == null) {
        problems.add("stylesheet " + name + " does not exist");
        continue;
      }
      try {
        out.put(name, CompiledStylesheet.compile(source));
      } catch (IllegalArgumentException e) {
        problems.add("stylesheet " + name + ": " + e.getMessage());
      }
    }
    return out;
  }

  private static String resource(String path) {
    try (InputStream in = PartnerRouteLoader.class.getClassLoader().getResourceAsStream(path)) {
      return in == null ? null : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * The parameters each auth scheme of ticket 87 needs: {@code basic} a {@code userField} (read
   * from OpenBao with the password), {@code oauth2-cc} a {@code clientId} and a {@code tokenUrl}
   * (held to the record's hosts like {@code url}), {@code jws} an {@code alg} it supports.
   */
  private static void authProblems(
      String id, String auth, Map<String, String> params, Calls calls, List<String> security, List<String> problems) {
    switch (auth) {
      case "basic" -> {
        String user = params.getOrDefault("userField", "");
        if (user.isEmpty()) {
          problems.add("auth basic needs a userField");
        } else {
          calls.secretFields.add(user);
        }
      }
      case "oauth2-cc" -> {
        if (params.getOrDefault("clientId", "").isEmpty()) {
          problems.add("auth oauth2-cc needs a clientId");
        }
        String tokenUrl = params.getOrDefault("tokenUrl", "");
        if (tokenUrl.isEmpty()) {
          problems.add("auth oauth2-cc needs a tokenUrl");
        } else {
          calls.urls.add(tokenUrl);
        }
      }
      case "jws" -> {
        if (!DetachedJws.supports(params.get("alg"))) {
          problems.add("auth jws needs alg RS256 or ES256");
        }
      }
      default -> { }
    }
  }

  /**
   * The dry-run base followed by the path of {@code url}, rebuilt from the parsed parts (scheme,
   * host, port, path; never user info, query or fragment) and held to the strict parameter rule;
   * null when either is not a plain http(s) URL or the result breaks the rule.
   */
  static String dryRunUrl(String base, String url) {
    URI b;
    URI u;
    try {
      b = new URI(base);
      u = new URI(url);
    } catch (java.net.URISyntaxException e) {
      return null;
    }
    if (b.getScheme() == null || !Set.of("http", "https").contains(b.getScheme().toLowerCase(Locale.ROOT))
        || b.getHost() == null || b.getRawUserInfo() != null || b.getRawQuery() != null || b.getRawFragment() != null) {
      return null;
    }
    String path = trimmed(b.getRawPath()) + trimmed(u.getRawPath());
    if (!path.isEmpty() && (!PartnerConfig.safePath(path) || path.indexOf('{') >= 0 || path.indexOf('}') >= 0)) {
      return null;
    }
    String target;
    try {
      target = new URI(b.getScheme(), null, b.getHost(), b.getPort(), path.isEmpty() ? null : path, null, null).toString();
    } catch (java.net.URISyntaxException e) {
      return null;
    }
    String decoded = java.net.URLDecoder.decode(target, java.nio.charset.StandardCharsets.UTF_8);
    if (decoded.chars().anyMatch(c -> "%?&#".indexOf(c) >= 0) || decoded.contains("{{") || decoded.contains("}}")
        || decoded.contains("${") || !decoded.equals(target)) {
      return null;
    }
    return target;
  }

  private static String trimmed(String path) {
    return path == null ? "" : path.replaceAll("/+$", "");
  }

  private int bulkhead(String id, PartnerConfig record) {
    if (record.resilience() != null && record.resilience().bulkheadMaxConcurrentCalls() != null) {
      return record.resilience().bulkheadMaxConcurrentCalls();
    }
    // the instance resilience4j.bulkhead.instances.<id> configures, else the default one
    return bulkheads.bulkhead(id).getBulkheadConfig().getMaxConcurrentCalls();
  }

  private static String scheme(String uri) {
    int colon = uri.indexOf(':');
    return colon < 0 ? "?" : uri.substring(0, colon);
  }

  // --- 6. Camel parse -------------------------------------------------------------------------

  private RouteDefinition parse(String id, String routeId, String yaml, List<String> security, List<String> problems) {
    try {
      Collection<RoutesBuilder> builders =
          PluginHelper.getRoutesLoader(camel).findRoutesBuilders(ResourceHelper.fromString(routeId + ".yaml", yaml));
      List<RouteDefinition> routes = new ArrayList<>();
      for (RoutesBuilder b : builders) {
        if (!(b instanceof RouteBuilder rb)) {
          security.add("unexpected route builder");
          continue;
        }
        RoutesDefinition defs = rb.configureRoutes(camel);
        if (!rb.getRouteTemplateCollection().getRouteTemplates().isEmpty()
            || !rb.getTemplatedRouteCollection().getTemplatedRoutes().isEmpty()
            || !rb.getRestCollection().getRests().isEmpty()
            || (rb instanceof RouteConfigurationBuilder rcb
                && !rcb.getRouteConfigurationCollection().getRouteConfigurations().isEmpty())
            || !defs.getOnExceptions().isEmpty()
            || !defs.getIntercepts().isEmpty()
            || !defs.getInterceptFroms().isEmpty()
            || !defs.getInterceptSendTos().isEmpty()
            || !defs.getOnCompletions().isEmpty()) {
          security.add("only one route, without templates, rests, error handling or interceptors");
        }
        routes.addAll(defs.getRoutes());
      }
      if (routes.size() != 1) {
        security.add("exactly one route is required");
        return null;
      }
      return routes.getFirst();
    } catch (Exception e) {
      problems.add("Camel cannot parse the route of " + id + ": " + firstLine(e.getMessage()));
      return null;
    }
  }

  // --- 7. allowlist of the parsed model ---------------------------------------------------------

  /**
   * The route's own steps: Camel prepends the loader's {@code routeConfiguration} handlers ({@code
   * onException}) to every route it prepares; those (the very instances of {@value
   * PartnerRouteConfiguration#ID}) are not the partner's and are left out. Any other error handler
   * stays and is refused.
   */
  private List<ProcessorDefinition<?>> partnerOutputs(RouteDefinition definition) {
    var config = ((org.apache.camel.model.ModelCamelContext) camel).getRouteConfigurationDefinition(PartnerRouteConfiguration.ID);
    List<ProcessorDefinition<?>> own = new ArrayList<>();
    for (ProcessorDefinition<?> node : definition.getOutputs()) {
      boolean fromConfig =
          config != null
              && node instanceof org.apache.camel.model.OnExceptionDefinition
              && config.getOnExceptions().stream().anyMatch(c -> c == node || c.equals(node)
                  || (c.getExceptions().equals(((org.apache.camel.model.OnExceptionDefinition) node).getExceptions())
                      && !((org.apache.camel.model.OnExceptionDefinition) node).isRouteScoped()));
      if (!fromConfig) {
        own.add(node);
      }
    }
    return own;
  }

  private static void walk(List<ProcessorDefinition<?>> outputs, List<String> security, List<String> problems) {
    for (ProcessorDefinition<?> node : outputs) {
      if (!ALLOWED_NODES.contains(node.getClass())) {
        security.add("'" + node.getShortName() + "' is not allowed in a partner route");
        continue;
      }
      switch (node) {
        case ToDefinition to -> {
          if (to.getUri() == null || !to.getUri().startsWith("kamelet:mw-")) {
            security.add("'to' may call only the mw-* Kamelets");
          }
        }
        case SetHeaderDefinition h -> {
          String name = h.getName() == null ? "" : h.getName().toLowerCase(Locale.ROOT);
          if (name.startsWith("camel") || name.startsWith("org.apache.camel") || name.startsWith("mw")) {
            security.add("setHeader may not set a Camel* or mw* header (" + h.getName() + ")");
          }
          expression(h.getExpression(), security);
        }
        case LogDefinition log -> {
          if (log.getLogger() != null || log.getMarker() != null || log.getLogLanguage() != null) {
            security.add("log may set only its message, level and name");
          }
          tokens(log.getMessage(), security);
          if (log.getMessage() != null && PLACEHOLDER.matcher(log.getMessage()).results()
              .anyMatch(m -> m.group(1).trim().equals("body"))) {
            security.add("log may not write the body (personal data)");
          }
        }
        case MarshalDefinition m -> dataFormat(m.getDataFormatType(), security);
        case UnmarshalDefinition u -> dataFormat(u.getDataFormatType(), security);
        case ExpressionNode e -> expression(e.getExpression(), security);
        default -> {
          // choice, otherwise: their children are walked below
        }
      }
      if (node instanceof ChoiceDefinition choice) {
        for (WhenDefinition w : choice.getWhenClauses()) {
          expression(w.getExpression(), security);
          walk(w.getOutputs(), security, problems);
        }
        if (choice.getOtherwise() != null) {
          walk(choice.getOtherwise().getOutputs(), security, problems);
        }
      }
    }
  }

  private static void expression(ExpressionDefinition e, List<String> security) {
    if (e == null) {
      return;
    }
    String language = e.getLanguage();
    if (!"constant".equals(language) && !"simple".equals(language)) {
      security.add("expression language " + language + " is not allowed (constant or simple only)");
      return;
    }
    if (e instanceof org.apache.camel.model.language.TypedExpressionDefinition typed
        && (typed.getResultTypeName() != null || typed.getResultType() != null)) {
      security.add("an expression may not name a result type");
    }
    if ("simple".equals(language)) {
      tokens(e.getExpression(), security);
    }
  }

  /** Every {@code ${...}} must read the body, a header, a variable or the date. */
  private static void tokens(String text, List<String> security) {
    if (text == null) {
      return;
    }
    Matcher m = PLACEHOLDER.matcher(text);
    while (m.find()) {
      if (!SIMPLE_TOKEN.matcher(m.group(1).trim()).matches()) {
        security.add("simple may read only ${body}, ${header.x}, ${variable.x} and ${date:now}, not ${" + m.group(1) + "}");
      }
    }
  }

  private static void dataFormat(DataFormatDefinition df, List<String> security) {
    if (!(df instanceof JsonDataFormat json)
        || (json.getLibrary() != null && json.getLibrary() != JsonLibrary.Jackson)
        || json.getUnmarshalTypeName() != null || json.getUnmarshalType() != null
        || json.getObjectMapper() != null || json.getJsonViewTypeName() != null
        || json.getCollectionTypeName() != null || json.getModuleClassNames() != null
        || json.getModuleRefs() != null || json.getSchemaResolver() != null
        || json.getAllowUnmarshallType() != null || json.getAllowJmsType() != null
        || json.getAutoDiscoverObjectMapper() != null || json.getAutoDiscoverSchemaResolver() != null
        || json.getNamingStrategy() != null) {
      security.add("marshal/unmarshal may use plain JSON (Jackson) only");
    }
  }

  // --- 8. the end of the route ------------------------------------------------------------------

  private static void ending(List<ProcessorDefinition<?>> outputs, List<String> problems) {
    int n = outputs.size();
    if (n < 2 || !isKamelet(outputs.get(n - 1), "mw-audit")) {
      problems.add("the route must end with mw-classify then mw-audit");
      return;
    }
    ProcessorDefinition<?> before = outputs.get(n - 2);
    if (isKamelet(before, "mw-classify")) {
      return;
    }
    if (before instanceof ChoiceDefinition choice && !choice.getWhenClauses().isEmpty()) {
      boolean all = choice.getWhenClauses().stream().allMatch(w -> endsWithClassify(w.getOutputs()));
      if (all && (choice.getOtherwise() == null || endsWithClassify(choice.getOtherwise().getOutputs()))) {
        return;
      }
    }
    problems.add("the route must end with mw-classify then mw-audit");
  }

  private static boolean endsWithClassify(List<ProcessorDefinition<?>> outputs) {
    return !outputs.isEmpty() && isKamelet(outputs.getLast(), "mw-classify");
  }

  private static boolean isKamelet(ProcessorDefinition<?> node, String name) {
    if (!(node instanceof ToDefinition to) || to.getUri() == null) {
      return false;
    }
    String uri = to.getUri();
    return uri.equals("kamelet:" + name) || uri.startsWith("kamelet:" + name + "?");
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static String dump(Map<String, Object> route) {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    return new Yaml(options).dump(List.of(Map.of("route", route)));
  }

  private static String firstLine(String s) {
    if (s == null) {
      return "";
    }
    int nl = s.indexOf('\n');
    return nl < 0 ? s : s.substring(0, nl);
  }
}
