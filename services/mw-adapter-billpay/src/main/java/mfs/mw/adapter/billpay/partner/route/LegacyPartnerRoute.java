package mfs.mw.adapter.billpay.partner.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import mfs.mw.adapter.billpay.partner.config.PartnerConfig;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Records of the old shape (ticket 20 to 81: {@code template} + {@code baseUrl} + {@code
 * operations}, no {@code route}) as the equivalent partner route, so the existing seeds and
 * published records keep loading for one release (ADR D26; ticket 84 drops the old fields). Each
 * operation with an outbound path becomes one {@code choice} branch on {@code mwOperation}:
 * {@code mw-map} (when it has a request mapping), {@code mw-http-call} (the record's {@code
 * auth}), {@code mw-classify} (its definite statuses; PENDING only for {@code
 * rest-json-async-callback}); the route ends with {@code mw-audit}. Its mappings are named {@code
 * <OPERATION>.request} / {@code <OPERATION>.response}. The translated route goes through the same
 * loader checks as any other.
 */
public final class LegacyPartnerRoute {

  private LegacyPartnerRoute() {}

  /** The record with its route and mappings filled in; {@code hosts} when it names none itself. */
  public static PartnerConfig translate(String id, PartnerConfig config, List<String> hosts) {
    Map<String, String> mappings = new LinkedHashMap<>(config.mappings());
    List<Object> whens = new ArrayList<>();
    String base = config.baseUrl().toString().replaceAll("/+$", "");
    boolean pending = PartnerConfig.ASYNC_CALLBACK.equals(config.template());
    config.operations().forEach(
        (name, op) -> {
          if ("CALLBACK".equals(name) && !pending) {
            return; // callbacks only for the async template, as before
          }
          if (op.response() != null) {
            mappings.put(name + ".response", op.response());
          }
          if (op.request() != null && !op.request().isBlank()) {
            mappings.put(name + ".request", op.request());
          }
          if ("CALLBACK".equals(name)) {
            return; // inbound only
          }
          List<Object> steps = new ArrayList<>();
          if (op.request() != null && !op.request().isBlank()) {
            steps.add(Map.of("to", "kamelet:mw-map?mapping=" + name + ".request"));
          }
          steps.add(Map.of("to", "kamelet:mw-http-call?url=" + base + "&method=" + op.method() + "&path=" + op.path() + auth(config)));
          steps.add(Map.of(
              "to",
              "kamelet:mw-classify?mapping=" + name + ".response&definiteStatuses="
                  + op.definiteStatuses().stream().map(String::valueOf).collect(Collectors.joining(","))
                  + "&pending=" + pending));
          Map<String, Object> when = new LinkedHashMap<>();
          when.put("simple", "${header.mwOperation} == '" + name + "'");
          when.put("steps", steps);
          whens.add(when);
        });
    Map<String, Object> from = new LinkedHashMap<>();
    from.put("uri", "direct:partner-" + id);
    from.put("steps", List.of(Map.of("choice", Map.of("when", whens)), Map.of("to", "kamelet:mw-audit")));
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    String route = new Yaml(options).dump(List.of(Map.of("route", Map.of("from", from))));
    return config.withRoute(route, mappings, config.allowedHosts().isEmpty() ? hosts : config.allowedHosts());
  }

  private static String auth(PartnerConfig config) {
    PartnerConfig.Auth auth = config.auth();
    return switch (auth.scheme()) {
      case "api-key" ->
          "&auth=apikey&secretField=" + auth.credentialsRef()
              + (auth.params().containsKey("header") ? "&authHeader=" + auth.params().get("header") : "");
      case "hmac-sha256" ->
          "&auth=hmac-sha256&secretField=" + auth.credentialsRef()
              + (auth.params().containsKey("key-id") ? "&keyId=" + auth.params().get("key-id") : "");
      default -> "";
    };
  }
}
