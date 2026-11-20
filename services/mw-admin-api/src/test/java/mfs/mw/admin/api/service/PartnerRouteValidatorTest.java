package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.config.PartnerRouteProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The maker-side copy of the adapters' route loader rules (ADR D28 guardrail 2, ticket 83): the
 * same refusals as mw-adapter-billpay's {@code PartnerRouteLoaderTest}, each with the line and the
 * construct, so a maker sees them before any approval. The Camel YAML DSL JSON Schema runs first.
 */
class PartnerRouteValidatorTest {

  final PartnerRouteValidator validator =
      new PartnerRouteValidator(new PartnerRouteProperties(null), JsonMapper.builder().build());

  static final Map<String, String> MAPPINGS = Map.of("PAY.request", "{\"a\": reference}", "PAY.response", "{\"outcome\": \"UNKNOWN\"}");

  static String route(String steps) {
    return """
        - route:
            from:
              uri: direct:partner-acme
              steps:
        %s
        """.formatted(steps.indent(8).stripTrailing());
  }

  static final String GOOD = """
      - to: "kamelet:mw-map?mapping=PAY.request"
      - to: "kamelet:mw-http-call?url=https://api.acme.example&method=POST&path=/pay/{reference}&auth=apikey&secretField=api-key"
      - to: "kamelet:mw-classify?mapping=PAY.response&definiteStatuses=422"
      - to: "kamelet:mw-audit"
      """;

  List<PartnerRouteValidator.Problem> check(String yaml) {
    return validator.problems("acme", yaml, MAPPINGS, List.of("api.acme.example"));
  }

  /** The dev seeds (billagg with its SOAP branches for the SOAP billers, ocs and the operator OCS profiles, c2wbank) pass as they are. */
  @ParameterizedTest
  @ValueSource(strings = {"billagg", "ocs", "gp-ocs", "robi-ocs", "teletalk-ocs", "bl-ocs", "c2wbank",
      "bank-dbbl", "bank-brac", "bank-sonali", "bank-janata", "merchant-soap"})
  void theDevSeedsHaveNoProblems(String id) throws Exception {
    try (java.io.InputStream in = getClass().getResourceAsStream("/partner-seeds/dev/" + id + ".json")) {
      tools.jackson.databind.JsonNode config = JsonMapper.builder().build().readTree(in).get("config");
      Map<String, String> mappings = new java.util.LinkedHashMap<>();
      config.get("mappings").properties().forEach(e -> mappings.put(e.getKey(), e.getValue().asString()));
      List<String> hosts = new java.util.ArrayList<>();
      config.get("allowedHosts").forEach(h -> hosts.add(h.asString()));
      assertThat(validator.problems(id, config.get("route").asString(), mappings, hosts)).isEmpty();
    }
  }

  @Test
  void aGoodRouteHasNoProblems() {
    assertThat(check(route(GOOD))).isEmpty();
  }

  @Test
  void aRouteWithAChoiceEndingInClassifyOnEveryBranchHasNoProblems() {
    String steps = """
        - choice:
            when:
              - simple: "${header.mwOperation} == 'PAY'"
                steps:
                  - to: "kamelet:mw-map?mapping=PAY.request"
                  - to: "kamelet:mw-http-call?url=https://api.acme.example&method=POST&path=/pay"
                  - to: "kamelet:mw-classify?mapping=PAY.response"
            otherwise:
              steps:
                - setHeader: {name: X-Channel, constant: app}
                - to: "kamelet:mw-classify?mapping=PAY.response"
        - to: "kamelet:mw-audit"
        """;
    assertThat(check(route(steps))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "- to: \"exec:rm\"",
      "- to: \"bean:partnerGateway\"",
      "- to: \"http://localhost:8089/pay\"",
      "- toD: \"kamelet:mw-audit\"",
      "- bean: {ref: partnerGateway}",
      "- process: {ref: partnerGateway}",
      "- to: \"kamelet:mw-audit?x=${header.mwReference}\"",
      "- to: \"kamelet:mw-audit?location=file:/tmp/evil\"",
      "- to: \"kamelet:mw-audit?partnerId=billagg\"",
      "- to: \"kamelet:mw-audit?a=1&a=2\"",
      "- to: \"kamelet:mw-audit?a%2Eb=1\"",
      "- to: \"kamelet:other-kamelet\"",
      "- onException: {exception: [java.lang.Exception], steps: [{to: \"kamelet:mw-audit\"}]}",
      "- doTry: {steps: [{to: \"kamelet:mw-audit\"}]}",
      "- setHeader: {name: CamelHttpUri, constant: \"http://evil\"}",
      "- setHeader: {name: mwPartnerId, constant: other}",
      "- setBody: {groovy: \"1\"}",
      "- setBody: {simple: \"${bean:partnerGateway}\"}",
      "- setBody: {simple: \"${sys.user.home}\"}",
      "- log: \"${body}\"",
      "- setBody: {constant: \"{{hashicorp:secret:mw-adapter-billpay/other#key}}\"}",
      "- marshal: {jacksonXml: {}}",
  })
  void everyConstructOutsideTheAllowlistIsRefusedWithItsLine(String step) {
    List<PartnerRouteValidator.Problem> problems = check(route(step + "\n" + GOOD));

    assertThat(problems).as(step).isNotEmpty();
    // the step is the first one, on line 5 of the route
    assertThat(problems).as(step).anySatisfy(p -> assertThat(p.line()).isEqualTo(5));
  }

  @Test
  void aYamlDslSchemaErrorIsReportedWithItsLine() {
    List<PartnerRouteValidator.Problem> problems = check(route("- setHeader: {name: X-A, constant: a, nonsense: 1}\n" + GOOD));

    assertThat(problems).anySatisfy(p -> {
      assertThat(p.construct()).isEqualTo("YAML DSL schema");
      assertThat(p.line()).isEqualTo(5);
    });
  }

  @Test
  void aHostOutsideTheAllowedHostsAMissingMappingAndAMissingEndingAreRefused() {
    assertThat(check(route(GOOD.replace("https://api.acme.example", "https://evil.example"))))
        .anySatisfy(p -> assertThat(p.message()).contains("allowedHosts"));
    assertThat(check(route(GOOD.replace("mapping=PAY.request", "mapping=NOPE"))))
        .anySatisfy(p -> assertThat(p.message()).contains("mapping NOPE"));
    assertThat(check(route(GOOD.replace("- to: \"kamelet:mw-audit\"\n", ""))))
        .anySatisfy(p -> assertThat(p.message()).contains("mw-classify then mw-audit"));
  }

  @Test
  void anythingBesideTheOneRouteOrFromAnotherPartnerIsRefused() {
    String withBeans = "- beans:\n    - name: x\n      type: java.lang.Object\n" + route(GOOD);
    assertThat(check(withBeans)).anySatisfy(p -> assertThat(p.line()).isEqualTo(1));
    assertThat(check(route(GOOD).replace("- route:\n", "- route:\n    id: mine\n"))).isNotEmpty();
    assertThat(check(route(GOOD).replace("direct:partner-acme", "direct:partner-billagg")))
        .anySatisfy(p -> assertThat(p.message()).contains("direct:partner-acme"));
    assertThat(check("- route: [")).anySatisfy(p -> assertThat(p.message()).contains("not valid YAML"));
  }
}
