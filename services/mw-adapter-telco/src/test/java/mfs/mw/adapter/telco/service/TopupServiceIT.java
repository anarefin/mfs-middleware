package mfs.mw.adapter.telco.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static mfs.mw.adapter.telco.TelcoFixtures.expiredPay;
import static mfs.mw.adapter.telco.TelcoFixtures.newTxnId;
import static mfs.mw.adapter.telco.TelcoFixtures.pay;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import mfs.mw.adapter.telco.TestInfrastructure;
import mfs.mw.adapter.telco.contracts.ContractSchemas;
import mfs.mw.adapter.telco.dto.PartnerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link TopupService} at its own seam (T3): one request in, exactly one reply in the outbox for
 * its requestId (valid against {@code mw.partner.reply.v1}), the partner called at most once, and
 * the call audited ({@code mw.audit.event.v1}, S6). The OCS is WireMock; no Kafka consumer runs.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.telco.request-consumer.enabled=false", "mw.outbox.relay.enabled=false"})
@Import(TestInfrastructure.class)
class TopupServiceIT {

  static final String REPLY_SCHEMA = "events/mw-orchestrator/mw.partner.reply.v1.schema.json";
  static final WireMockServer OCS = new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    OCS.start();
  }

  @DynamicPropertySource
  static void ocsUrl(DynamicPropertyRegistry registry) {
    registry.add("mw.partners.instances.ocs.base-url", () -> "http://localhost:" + OCS.port());
  }

  private final TopupService service;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  TopupServiceIT(TopupService service, JdbcTemplate jdbc, JsonMapper json) {
    this.service = service;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void reset() {
    OCS.resetAll();
  }

  private void ocsAnswers(int status, String body) {
    OCS.stubFor(post(urlEqualTo("/ocs/v1/topup"))
        .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
  }

  private List<JsonNode> outbox(String topic, String key) {
    return jdbc.queryForList("select payload::text from outbox where topic = ? and msg_key = ? order by id", String.class, topic, key)
        .stream()
        .map(json::readTree)
        .toList();
  }

  private JsonNode onlyReply(PartnerRequest request) {
    List<JsonNode> replies = outbox(TopupService.REPLY_TOPIC, request.txnId());
    assertThat(replies).hasSize(1);
    JsonNode reply = replies.getFirst();
    assertThat(ContractSchemas.schema(REPLY_SCHEMA).validate(reply)).as(reply.toString()).isEmpty();
    assertThat(reply.path("requestId").asString()).isEqualTo(request.requestId());
    return reply;
  }

  @Test
  void aSuccessfulTopupWritesOneSuccessReplyAndAnAudit() {
    ocsAnswers(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\",\"message\":\"ok\",\"ocsRef\":\"OCS-1\"}");
    PartnerRequest request = pay(newTxnId(), "01711000001", 2000);

    service.process(request);

    JsonNode reply = onlyReply(request);
    assertThat(reply.path("payload").path("outcome").asString()).isEqualTo("SUCCESS");
    assertThat(reply.path("payload").path("partnerRef").asString()).isEqualTo("OCS-1");
    assertThat(outbox("mw.audit.event.v1", request.txnId())).singleElement()
        .satisfies(a -> assertThat(a.path("action").asString()).isEqualTo("partner.call.completed"));
  }

  @Test
  void aRedeliveredRequestIsRepliedOnce() {
    ocsAnswers(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\",\"message\":\"ok\",\"ocsRef\":\"OCS-2\"}");
    PartnerRequest request = pay(newTxnId(), "01711000001", 2000);

    service.process(request);
    service.process(request);

    onlyReply(request);
  }

  @Test
  void aPayPastItsDeadlineIsRefusedWithoutCallingThePartner() {
    PartnerRequest request = expiredPay(newTxnId(), "01711000001", 2000);

    service.process(request);

    JsonNode reply = onlyReply(request);
    assertThat(reply.path("payload").path("outcome").asString()).isEqualTo("FAILED");
    assertThat(reply.path("payload").path("errorCode").asString()).isEqualTo("MW-SYS-PARTNER_REQUEST_EXPIRED");
    OCS.verify(0, postRequestedFor(anyUrl()));
  }

  @Test
  void aRequestThatCannotBeMappedIsFailedWithoutCallingThePartner() {
    PartnerRequest request = pay(newTxnId(), "01711000001", 0);

    service.process(request);

    JsonNode reply = onlyReply(request);
    assertThat(reply.path("payload").path("outcome").asString()).isEqualTo("FAILED");
    assertThat(reply.path("payload").path("errorCode").asString()).isEqualTo("MW-VAL-INVALID_PARTNER_REQUEST");
    OCS.verify(0, postRequestedFor(anyUrl()));
  }
}
