package mfs.mw.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.networknt.schema.Schema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.contracts.ContractSchemas;
import mfs.mw.admin.api.contracts.OpenApiSchemas;
import mfs.mw.admin.api.messaging.KafkaTestTopics;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A partner route through maker-checker (ADR D26, ticket 83): the maker submits an UPSERT_ROUTE,
 * which the copy of the adapters' loader rules checks first (a forbidden construct or a YAML DSL
 * schema error is refused {@code 400 MW-VAL-041} with its line, before any change request exists);
 * a checker approves; the approval writes {@code mw.config.changed.v1} with the route fields and
 * the family through the outbox and the version is PENDING_DRY_RUN; the adapter's {@code
 * mw.partner.route.status.v1} (LIVE / REJECTED, on Kafka) sets the route status the console shows,
 * and the Kamelet usage lists the live routes per Kamelet.
 */
@SpringBootTest(properties = {"mw.outbox.relay.enabled=false", "mw.partner-routes.status-consumer.enabled=true"})
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
@DirtiesContext
class PartnerRouteApiIT {

  static final String CONTRACT = "api/mw-admin-api/admin.v1.openapi.yaml";
  static final String STATUS_TOPIC = "mw.partner.route.status.v1";

  static final RequestPostProcessor MAKER = operator("ops-maker01", "viewer", "ops-maker");
  static final RequestPostProcessor CHECKER = operator("ops-checker01", "viewer", "ops-checker");

  static final String ROUTE = """
      - route:
          from:
            uri: direct:partner-newco
            steps:
              - to: "kamelet:mw-map?mapping=PAY.request"
              - to: "kamelet:mw-http-call?url=https://api.newco.example&method=POST&path=/v1/payments&auth=apikey&secretField=api-key"
              - to: "kamelet:mw-classify?mapping=PAY.response&definiteStatuses=422"
              - to: "kamelet:mw-audit"
      """;

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final String bootstrap;

  @Autowired
  PartnerRouteApiIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json,
      @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.bootstrap = bootstrap;
  }

  @BeforeAll
  static void topics() {
    KafkaTestTopics.createWithRetries(STATUS_TOPIC, "mw-admin-api", 3, 1);
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from outbox");
    jdbc.update("delete from change_request");
    jdbc.update("delete from partner.partner where id = 'newco'");
  }

  static RequestPostProcessor operator(String username, String... roles) {
    return jwt().jwt(j -> j.subject("sub-" + username).issuer("http://keycloak:8080/realms/ops")
        .claim("preferred_username", username).claim("realm_access", Map.of("roles", List.of(roles))));
  }

  String body(String route) {
    return json.writeValueAsString(Map.of(
        "action", "UPSERT_ROUTE",
        "reason", "Onboard NewCo (ticket 83)",
        "family", "billpay",
        "config", Map.of(
            "route", route,
            "mappings", Map.of(
                "PAY.request", "{\"ref\": reference, \"account\": creditParty.id}",
                "PAY.response", "status = 200 ? {\"outcome\": \"SUCCESS\", \"partnerRef\": body.receipt} : {\"outcome\": \"UNKNOWN\"}"),
            "allowedHosts", List.of("api.newco.example"),
            "timeoutMs", 20000,
            "errorMap", Map.of("E1", Map.of("suffix", "ACCOUNT_CLOSED", "retryable", false)),
            "ambiguousCodes", List.of("E9"),
            "sensitiveFields", Map.of("account", "ACCOUNT"),
            "dryRun", Map.of("operation", "PAY", "sample", Map.of("creditParty", Map.of("type", "BILLER_ACCOUNT", "id", "77001234"))))));
  }

  MvcTestResult ask(String body) {
    return mvc.post().uri("/admin/v1/partners/newco/change-requests").with(MAKER)
        .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  JsonNode read(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }

  static void assertValid(String path, String method, String status, JsonNode body) {
    Schema schema = OpenApiSchemas.response(CONTRACT, path, method, status);
    assertThat(schema.validate(body)).as("%s", body).isEmpty();
  }

  JsonNode partner() {
    MvcTestResult r = mvc.get().uri("/admin/v1/partners/newco").with(CHECKER).exchange();
    assertThat(r).hasStatus(200);
    JsonNode p = read(r);
    assertValid("/admin/v1/partners/{partnerId}", "get", "200", p);
    return p;
  }

  void status(String event) {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
      producer.send(new ProducerRecord<>(STATUS_TOPIC, "newco", event)).get();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  JsonNode awaitRoute(String status) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    JsonNode p = partner();
    while (!status.equals(p.path("route").path("status").asString()) && System.nanoTime() < deadline) {
      Thread.sleep(100);
      p = partner();
    }
    return p;
  }

  @Test
  void aForbiddenConstructIsRefusedAtSubmitWithItsLineBeforeAnyApproval() {
    MvcTestResult r = ask(body(ROUTE.replace("- to: \"kamelet:mw-map?mapping=PAY.request\"", "- bean: {ref: partnerGateway}")));

    assertThat(r).hasStatus(400);
    JsonNode error = read(r);
    assertThat(error.path("code").asString()).isEqualTo("MW-VAL-041");
    assertThat(error.path("details").toString()).contains("config.route", "line 5", "bean");
    assertThat(jdbc.queryForObject("select count(*) from change_request", Integer.class)).isZero();
  }

  @Test
  void aYamlDslSchemaErrorIsReportedWithItsLine() {
    MvcTestResult r = ask(body(ROUTE.replace(
        "- to: \"kamelet:mw-audit\"", "- setHeader: {name: X-A, constant: a, nonsense: 1}\n        - to: \"kamelet:mw-audit\"")));

    assertThat(r).hasStatus(400);
    assertThat(read(r).path("details").toString()).contains("line 8", "YAML DSL schema");
  }

  @Test
  void submitApproveThenTheAdaptersStatusMakesItLiveAndAFailedVersionIsShownRejected() throws Exception {
    MvcTestResult asked = ask(body(ROUTE));
    assertThat(asked).hasStatus(201);
    String id = read(asked).path("id").asString();
    assertThat(jdbc.queryForObject("select count(*) from outbox where topic = 'mw.config.changed.v1'", Integer.class)).isZero();

    MvcTestResult approved = mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(CHECKER).exchange();

    assertThat(approved).hasStatus(200);
    JsonNode record = json.readTree(jdbc.queryForObject(
        "select payload::text from outbox where topic = 'mw.config.changed.v1'", String.class));
    assertThat(ContractSchemas.schema("events/mw-admin-api/mw.config.changed.v1.schema.json").validate(record))
        .as("%s", record).isEmpty();
    JsonNode value = record.path("value");
    assertThat(value.path("family").asString()).isEqualTo("billpay");
    assertThat(value.path("route").asString()).contains("direct:partner-newco");
    assertThat(value.path("allowedHosts").toString()).isEqualTo("[\"api.newco.example\"]");
    assertThat(value.path("dryRun").path("operation").asString()).isEqualTo("PAY");
    assertThat(value.path("mappings").path("PAY.request").asString()).contains("reference");
    assertThat(value.has("baseUrl")).isFalse();
    JsonNode pending = partner();
    assertThat(pending.path("version").asLong()).isEqualTo(1);
    assertThat(pending.path("route").path("status").asString()).isEqualTo("PENDING_DRY_RUN");
    assertThat(pending.path("config").path("route").asString()).isEqualTo(ROUTE);

    status("""
        {"schemaVersion":1,"partnerId":"newco","version":1,"family":"billpay","status":"LIVE","problems":[],
         "kamelets":["mw-audit","mw-auth-apikey","mw-classify","mw-http-call","mw-map"],
         "reportedBy":"mw-adapter-billpay","reportedAt":"2026-11-18T10:00:05Z"}""");
    JsonNode live = awaitRoute("LIVE");
    assertThat(live.path("route").path("liveVersion").asLong()).isEqualTo(1);
    MvcTestResult usage = mvc.get().uri("/admin/v1/partners/kamelet-usage").with(CHECKER).exchange();
    assertThat(usage).hasStatus(200);
    JsonNode u = read(usage);
    assertValid("/admin/v1/partners/kamelet-usage", "get", "200", u);
    assertThat(u.path("kamelets").toString()).contains("\"name\":\"mw-http-call\"", "\"partnerId\":\"newco\"");

    // version 2: approved, then refused by the adapter's dry-run; version 1 stays the live one
    String second = read(ask(body(ROUTE.replace("definiteStatuses=422", "definiteStatuses=409")))).path("id").asString();
    assertThat(mvc.post().uri("/admin/v1/change-requests/{id}/approve", second).with(CHECKER).exchange()).hasStatus(200);
    status("""
        {"schemaVersion":1,"partnerId":"newco","version":2,"family":"billpay","status":"REJECTED",
         "problems":["dry-run PAY gave no usable answer: HTTP_5XX"],
         "reportedBy":"mw-adapter-billpay","reportedAt":"2026-11-18T10:05:05Z"}""");
    // ticket 84: the rejected current version is answered by republishing version 1 as version 3
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    JsonNode rolledBack = partner();
    while (rolledBack.path("version").asLong() != 3 && System.nanoTime() < deadline) {
      Thread.sleep(100);
      rolledBack = partner();
    }
    assertThat(rolledBack.path("version").asLong()).isEqualTo(3);
    assertThat(rolledBack.path("updatedBy").asString()).isEqualTo("system/rollback");
    assertThat(rolledBack.path("config").path("route").asString()).isEqualTo(ROUTE);
    assertThat(rolledBack.path("route").path("status").asString()).isEqualTo("PENDING_DRY_RUN");
    assertThat(rolledBack.path("route").path("liveVersion").asLong()).isEqualTo(1);
    assertThat(jdbc.queryForObject(
            "select problems::text from partner.partner_route where partner_id = 'newco' and version = 2", String.class))
        .contains("HTTP_5XX");
  }

  /** The adapters' candidate names carry "~", which no id can hold: no id needs reserving. */
  @Test
  void anIdThatLooksLikeACandidateIsAnOrdinaryPartner() {
    jdbc.update("delete from partner.partner where id = 'newco-candidate-v2'");
    MvcTestResult r = mvc.post().uri("/admin/v1/partners/newco-candidate-v2/change-requests").with(MAKER)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(ROUTE.replace("partner-newco", "partner-newco-candidate-v2"))).exchange();

    assertThat(r).hasStatus(201);
  }

  @Test
  void aDryRunSandboxOutsideTheAllowedHostsIsRefused() {
    String b = body(ROUTE).replace("\"operation\":\"PAY\"", "\"operation\":\"PAY\",\"baseUrl\":\"https://sandbox.evil.example\"");
    MvcTestResult r = ask(b);

    assertThat(r).hasStatus(400);
    assertThat(read(r).path("details").toString()).contains("config.dryRun.baseUrl");
  }
}
